"""Web pack: safe page-object shells and static framework policy."""

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


class WebPack:
    domain = TestDomain.WEB
    policy_rules = (
        "thin-step-definitions",
        "WebAction-only",
        "no-direct-page",
        "no-Thread.sleep",
        "Slf4j-page-objects",
        "typed-locator-names",
    )

    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact:
        component = java_pascal_identifier(
            task.impacted_components[0] if task.impacted_components else "Generated"
        )
        gaps = []
        todo = "Use only a locally verified WebAction interaction."
        if not context.local_selectors:
            gaps.append("no local selector evidence; locator remains unresolved")
            todo = (
                "TODO: resolve locator from local evidence before adding a "
                "WebAction interaction."
            )
        content = (
            f"package {context.target_package};\n\n"
            "import lombok.extern.slf4j.Slf4j;\n\n"
            "@Slf4j\n"
            f"public class {component}Page {{\n"
            "    public void waitUntilReady() {\n"
            f"        // {todo}\n"
            "    }\n"
            "}\n"
        )
        return GeneratedArtifact(
            "web-page-object",
            content,
            confidence_score=0.8 if not gaps else 0.6,
            unresolved_gaps=gaps,
        )

    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        if not artifact.kind.startswith("web-"):
            return ValidationResult(True)
        errors: list[str] = []
        if re.search(r"\bThread\s*\.\s*sleep\s*\(", artifact.content):
            errors.append("Thread.sleep is prohibited")
        if re.search(r"\bpage\s*\.", artifact.content):
            errors.append("web artifacts must not use direct page interactions")
        if re.search(r"\b(?:sample_mobiler|playwright)\s*\.", artifact.content):
            errors.append("web artifacts must use WebAction, not direct sample_mobiler access")
        if artifact.kind == "web-page-object" and "@Slf4j" not in artifact.content:
            errors.append("web page object must be annotated with @Slf4j")
        locator_names = re.findall(
            r"\b(?:Locator|By)\s+([A-Za-z_]\w*)\s*[;=]", artifact.content
        )
        invalid_names = [
            name
            for name in locator_names
            if not name.startswith(("btn", "txt", "drpdwn", "lbl", "chkbox"))
        ]
        if invalid_names:
            errors.append(
                "web locator fields require btn/txt/drpdwn/lbl/chkbox prefix: "
                + ", ".join(invalid_names)
            )
        return ValidationResult(
            not errors,
            errors,
            suggested_repair_type=(
                RepairType.HUMAN_REVIEW if errors else RepairType.LOCAL_FIX
            ),
        )
