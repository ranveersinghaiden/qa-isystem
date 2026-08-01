"""Local-first orchestration and explicit LLM escalation policy."""

from __future__ import annotations

from typing import Any

from qa_engine_context import LocalContextBuilder
from qa_engine_generators import (
    DeterministicBddGenerator,
    DeterministicPageObjectGenerator,
    DeterministicReviewEngine,
    DeterministicStepDefinitionGenerator,
    DeterministicTestSkeletonGenerator,
)
from qa_engine_models import (
    AutomationTask,
    GeneratedArtifact,
    GenerationMode,
    GenerationPlan,
    LlmGenerationRequest,
    LlmProvider,
    LocalContextBundle,
    RepairType,
    RunObservability,
    TaskType,
    ValidationResult,
    to_jsonable,
)
from qa_engine_packs import PackRegistry, default_pack_registry
from qa_engine_repair import LocalRepairEngine
from qa_engine_validators import LocalValidators


class GenerationRouter:
    """Attempts local generation and validation before any model invocation."""

    def __init__(
        self,
        builder: LocalContextBuilder,
        cheap_provider: LlmProvider | None = None,
        premium_provider: LlmProvider | None = None,
        registry: PackRegistry | None = None,
    ) -> None:
        self.builder = builder
        self.cheap_provider = cheap_provider
        self.premium_provider = premium_provider
        self.registry = registry or default_pack_registry()
        self.bdd = DeterministicBddGenerator()
        self.skeleton = DeterministicTestSkeletonGenerator()
        self.steps = DeterministicStepDefinitionGenerator()
        self.page_objects = DeterministicPageObjectGenerator()
        self.review = DeterministicReviewEngine(builder.root)
        self.validators = LocalValidators()
        self.repair = LocalRepairEngine()

    def route(self, task: AutomationTask) -> dict[str, Any]:
        context = self.builder.build(task)
        selection = self.registry.select(task)
        context.selected_test_domain = selection.pack.domain.value
        context.pack_inference = selection.source
        context.conventions.extend(selection.pack.policy_rules)
        events = [
            "local_context",
            f"pack:{selection.pack.domain.value}:{selection.source}",
            "deterministic_generation",
        ]
        repair_attempted = False
        provider_attempts: list[tuple[str, GenerationMode]] = []
        artifact = (
            self.review.review(context)
            if task.task_type == TaskType.VALIDATION_ONLY
            else selection.pack.generate(task, context)
        )
        if selection.note:
            artifact.unresolved_gaps.append(selection.note)
        validation = self.validators.validate(artifact, context, selection.pack)
        events.append("local_validation")
        if (
            not validation.valid
            and validation.suggested_repair_type == RepairType.LOCAL_FIX
        ):
            repair_attempted = True
            artifact = self.repair.repair(artifact, context, validation)
            validation = self.validators.validate(artifact, context, selection.pack)
            events.extend(["local_repair", "local_validation"])
        if selection.note:
            plan = GenerationPlan(
                GenerationMode.HUMAN_REVIEW,
                selection.note,
                context.template_match,
                artifact.unresolved_gaps + validation.errors,
                list(selection.pack.policy_rules),
            )
            return self._result(
                context,
                artifact,
                validation,
                plan,
                events + ["human_review"],
                repair_attempted,
                provider_attempts,
                selection.note,
            )
        if validation.valid and not artifact.unresolved_gaps:
            plan = GenerationPlan(
                GenerationMode.DETERMINISTIC,
                "local generation passed local validation",
                context.template_match,
                artifact.unresolved_gaps,
                list(selection.pack.policy_rules),
            )
            return self._result(
                context,
                artifact,
                validation,
                plan,
                events,
                repair_attempted,
                provider_attempts,
                selection.note,
            )

        provider, mode, reason = self._select_provider(task)
        plan = GenerationPlan(
            mode,
            reason,
            context.template_match,
            artifact.unresolved_gaps + validation.errors,
            list(selection.pack.policy_rules),
            provider.name if provider else "",
        )
        if provider is None:
            plan.generation_mode = GenerationMode.HUMAN_REVIEW
            plan.reason += "; no configured provider"
            return self._result(
                context,
                artifact,
                validation,
                plan,
                events + ["human_review"],
                repair_attempted,
                provider_attempts,
                selection.note,
            )

        response = provider.generate(
            LlmGenerationRequest(task, context, plan, artifact, validation)
        )
        provider_attempts.append((response.provider, mode))
        plan.provider = response.provider
        artifact.content = response.content
        validation = self.validators.validate(artifact, context, selection.pack)
        events.extend([f"llm:{response.provider}", "local_validation"])
        if validation.valid:
            return self._result(
                context,
                artifact,
                validation,
                plan,
                events,
                repair_attempted,
                provider_attempts,
                selection.note,
            )

        if mode == GenerationMode.CHEAP_LLM:
            retry = provider.generate(
                LlmGenerationRequest(task, context, plan, artifact, validation)
            )
            provider_attempts.append((retry.provider, mode))
            plan.provider = retry.provider
            artifact.content = retry.content
            validation = self.validators.validate(artifact, context, selection.pack)
            events.extend([f"llm:{retry.provider}", "local_validation"])
            if validation.valid:
                return self._result(
                    context,
                    artifact,
                    validation,
                    plan,
                    events,
                    repair_attempted,
                    provider_attempts,
                    selection.note,
                )
            if self.premium_provider is not None:
                plan.generation_mode = GenerationMode.PREMIUM_LLM
                plan.reason = "cheap provider failed local validation twice"
                premium = self.premium_provider.generate(
                    LlmGenerationRequest(task, context, plan, artifact, validation)
                )
                provider_attempts.append((premium.provider, GenerationMode.PREMIUM_LLM))
                plan.provider = premium.provider
                artifact.content = premium.content
                validation = self.validators.validate(artifact, context, selection.pack)
                events.extend([f"llm:{premium.provider}", "local_validation"])
                if validation.valid:
                    return self._result(
                        context,
                        artifact,
                        validation,
                        plan,
                        events,
                        repair_attempted,
                        provider_attempts,
                        selection.note,
                    )

        plan.generation_mode = GenerationMode.HUMAN_REVIEW
        plan.reason = "provider output failed local validation"
        events.append("human_review")
        return self._result(
            context,
            artifact,
            validation,
            plan,
            events,
            repair_attempted,
            provider_attempts,
            selection.note,
        )

    def _select_provider(
        self, task: AutomationTask
    ) -> tuple[LlmProvider | None, GenerationMode, str]:
        if self.cheap_provider is not None:
            return (
                self.cheap_provider,
                GenerationMode.CHEAP_LLM,
                "local validation requires bounded model assistance",
            )
        if self.premium_provider is not None:
            return (
                self.premium_provider,
                GenerationMode.PREMIUM_LLM,
                "cheap provider is not configured",
            )
        return None, GenerationMode.HUMAN_REVIEW, "local evidence is insufficient"

    @staticmethod
    def _result(
        context: LocalContextBundle,
        artifact: GeneratedArtifact,
        validation: ValidationResult,
        plan: GenerationPlan,
        events: list[str],
        repair_attempted: bool,
        provider_attempts: list[tuple[str, GenerationMode]],
        selection_note: str,
    ) -> dict[str, Any]:
        unresolved_items = list(dict.fromkeys(
            artifact.unresolved_gaps
            + validation.errors
            + validation.missing_symbols
            + validation.duplicate_definitions
        ))
        categories: list[str] = []
        if selection_note:
            categories.append("pack_selection")
        if artifact.unresolved_gaps:
            categories.append("artifact_gap")
        if validation.errors:
            categories.append("validation_error")
        if validation.missing_symbols:
            categories.append("missing_symbol")
        if validation.duplicate_definitions:
            categories.append("duplicate_definition")
        provider, model_tier = ("", "")
        if provider_attempts:
            provider, mode = provider_attempts[-1]
            model_tier = mode.value
        if plan.generation_mode == GenerationMode.HUMAN_REVIEW:
            outcome, escalation_stage = "human_review_required", "human_review"
        elif provider_attempts:
            outcome, escalation_stage = "llm_escalation_pass", model_tier
        elif repair_attempted:
            outcome, escalation_stage = "local_repair_pass", "local_repair"
        else:
            outcome, escalation_stage = "deterministic_pass", "deterministic"
        validation_state = (
            "passed_with_unresolved_gaps"
            if validation.valid and unresolved_items
            else "passed"
            if validation.valid
            else "failed"
        )
        repair_state = (
            "repaired"
            if repair_attempted and validation.valid
            else "attempted_not_resolved"
            if repair_attempted
            else "not_needed"
        )
        observability = RunObservability(
            pack=context.selected_test_domain,
            domain=context.selected_test_domain,
            pack_selection=context.pack_inference,
            outcome=outcome,
            escalation_stage=escalation_stage,
            validation_state=validation_state,
            repair_state=repair_state,
            unresolved_gap_count=len(unresolved_items),
            unresolved_gap_categories=categories,
            provider=provider,
            model_tier=model_tier,
            events=events,
            llm_calls=len(provider_attempts),
        )
        return to_jsonable({
            "context": context,
            "artifact": artifact,
            "validation": validation,
            "plan": plan,
            "observability": observability,
        })
