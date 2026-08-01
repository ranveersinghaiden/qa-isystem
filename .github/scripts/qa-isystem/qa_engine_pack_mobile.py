"""Mobile pack: ActionEngine-only screen shell and policy checks."""

from __future__ import annotations

import re

from qa_engine_models import (
    AutomationTask,
    GeneratedArtifact,
    LocalContextBundle,
    RepairType,
    TestDomain,
    ValidationResult,
    java_pascal_identifier,
)


class MobilePack:
    domain = TestDomain.MOBILE
    policy_rules = (
        "ActionEngine-only",
        "accessibility-id-first",
        "no-PageFactory",
        "no-static-AppiumSample Mobiler",
        "no-AppConfig-in-steps",
    )

    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact:
        component = java_pascal_identifier(
            task.impacted_components[0] if task.impacted_components else "Generated"
        )
        gaps = []
        todo = "Use a locally verified ActionEngine interaction."
        if not context.local_selectors:
            gaps.append("no local mobile locator evidence; locator remains unresolved")
            todo = (
                "TODO: resolve accessibility id from local evidence before adding "
                "an ActionEngine interaction."
            )
        content = (
            f"public class {component}Screen {{\n"
            "    private final ActionEngine actionEngine;\n\n"
            f"    public {component}Screen(ActionEngine actionEngine) {{\n"
            "        this.actionEngine = actionEngine;\n"
            "    }\n\n"
            "    public void waitUntilReady() {\n"
            f"        // {todo}\n"
            "    }\n"
            "}\n"
        )
        return GeneratedArtifact(
            "mobile-screen",
            content,
            confidence_score=0.8 if not gaps else 0.6,
            unresolved_gaps=gaps,
        )

    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        if not artifact.kind.startswith("mobile-"):
            return ValidationResult(True)
        content = artifact.content
        errors: list[str] = []
        if re.search(r"@(?:Android|iOS)FindBy", content):
            errors.append("mobile artifacts must not use Appium PageFactory annotations")
        if re.search(r"static\s+(?:final\s+)?AppiumSample Mobiler\b", content):
            errors.append("mobile artifacts must not declare static AppiumSample Mobiler")
        if re.search(r"\b(?:sample_mobiler|appiumSample Mobiler)\s*\.", content):
            errors.append("mobile artifacts must use ActionEngine, not direct sample_mobiler calls")
        if artifact.kind == "mobile-step-definition" and "AppConfig.getProperty(" in content:
            errors.append("mobile step definitions must not use AppConfig.getProperty")
        if "AppiumBy.id(" in content and "accessibilityId(" not in content:
            errors.append("mobile locator policy requires accessibility id before id")
        return ValidationResult(
            not errors,
            errors,
            suggested_repair_type=(
                RepairType.HUMAN_REVIEW if errors else RepairType.LOCAL_FIX
            ),
        )
