"""Typed QA-pack contract, registry, and safe task-domain inference."""

from __future__ import annotations

import dataclasses
from typing import Protocol

from qa_engine_models import (
    AutomationTask,
    GeneratedArtifact,
    LocalContextBundle,
    TaskType,
    TestDomain,
    ValidationResult,
)


class GenerationPack(Protocol):
    """Domain policy; router owns context, repair, providers, and escalation."""

    domain: TestDomain
    policy_rules: tuple[str, ...]

    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact: ...

    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult: ...


@dataclasses.dataclass(frozen=True)
class PackSelection:
    pack: GenerationPack
    source: str
    note: str = ""


class PackRegistry:
    """Resolve one domain without guessing when evidence conflicts."""

    _TASK_DOMAINS = {
        TaskType.WEB_TEST_GENERATION: TestDomain.WEB,
        TaskType.API_TEST_GENERATION: TestDomain.API,
        TaskType.MOBILE_TEST_GENERATION: TestDomain.MOBILE,
        TaskType.BDD_GENERATION: TestDomain.BDD,
        TaskType.STEP_DEFINITION_GENERATION: TestDomain.BDD,
    }
    _TARGET_MARKERS = {
        TestDomain.BDD: ("bdd", "cucumber", "gherkin"),
        TestDomain.WEB: ("web", "playwright", "selenium"),
        TestDomain.API: ("api", "rest", "http"),
        TestDomain.MOBILE: ("mobile", "appium", "android", "ios"),
    }
    _PATH_MARKERS = {
        TestDomain.WEB: ("web-automation", "e2e-tests", "mobile_app-web"),
        TestDomain.API: ("api-support", "/api/", "api-client"),
        TestDomain.MOBILE: ("mobile-qa_tooling", "appium", "/mobile/"),
    }

    def __init__(self, packs: list[GenerationPack]) -> None:
        self._packs = {pack.domain: pack for pack in packs}
        if TestDomain.BDD not in self._packs:
            raise ValueError("BDD pack is required as safe fallback")

    def select(self, task: AutomationTask) -> PackSelection:
        explicit = self._domain(task.test_domain)
        if explicit is not None:
            return self._selection(explicit, "explicit")
        if task.test_domain is not None:
            return PackSelection(
                self._packs[TestDomain.BDD],
                "default",
                "unsupported explicit domain; defaulted to generic BDD",
            )
        task_domain = self._TASK_DOMAINS.get(task.task_type)
        if task_domain is not None:
            return self._selection(task_domain, "task_type")
        inferred = self._infer(task)
        if len(inferred) == 1:
            domain = next(iter(inferred))
            return self._selection(domain, "inferred")
        if len(inferred) > 1:
            return PackSelection(
                self._packs[TestDomain.BDD],
                "ambiguous-fallback",
                "conflicting domain evidence; defaulted to BDD for human review",
            )
        return PackSelection(
            self._packs[TestDomain.BDD],
            "default",
            "no domain evidence; defaulted to generic BDD",
        )

    def _selection(self, domain: TestDomain, source: str) -> PackSelection:
        pack = self._packs.get(domain)
        if pack is not None:
            return PackSelection(pack, source)
        return PackSelection(
            self._packs[TestDomain.BDD],
            "unregistered-fallback",
            f"{source} selection resolved {domain.value}, but its pack is "
            "unregistered; defaulted BDD requires human review",
        )

    @staticmethod
    def _domain(value: TestDomain | str | None) -> TestDomain | None:
        if isinstance(value, TestDomain):
            return value
        if isinstance(value, str):
            try:
                return TestDomain(value.lower())
            except ValueError:
                return None
        return None

    def _infer(self, task: AutomationTask) -> set[TestDomain]:
        target = task.target_test_type.lower()
        paths = " ".join(task.impacted_components).lower()
        found = {
            domain
            for domain, markers in self._TARGET_MARKERS.items()
            if any(marker in target for marker in markers)
        }
        found.update(
            domain
            for domain, markers in self._PATH_MARKERS.items()
            if any(marker in paths for marker in markers)
        )
        non_bdd = found - {TestDomain.BDD}
        if non_bdd:
            found = non_bdd
        return found


def default_pack_registry() -> PackRegistry:
    """Build built-in packs lazily so packs only depend on shared contracts."""

    from qa_engine_pack_api import ApiPack
    from qa_engine_pack_bdd import BddPack
    from qa_engine_pack_mobile import MobilePack
    from qa_engine_pack_web import WebPack

    return PackRegistry([BddPack(), WebPack(), ApiPack(), MobilePack()])
