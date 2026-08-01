"""Deterministic artifact generators and local static review."""

from __future__ import annotations

import re
from pathlib import Path

from qa_engine_context import read_text
from qa_engine_models import AutomationTask, GeneratedArtifact, LocalContextBundle


class DeterministicBddGenerator:
    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact:
        text = " ".join(task.acceptance_criteria + [task.pr_diff_summary]).lower()
        template, scenarios = self._template(text, task)
        tags = self._tags(context)
        rendered = [f"{tags}\nFeature: {self._feature_name(task)}", ""]
        for scenario in scenarios:
            rendered.extend([
                f"Scenario: {scenario['name']}",
                f"  Given {scenario['given']}",
                f"  When {scenario['when']}",
                f"  Then {scenario['then']}",
                "",
            ])
        return GeneratedArtifact(
            "gherkin",
            "\n".join(rendered).rstrip() + "\n",
            scenarios,
            confidence_score=0.9 if task.acceptance_criteria else 0.55,
            unresolved_gaps=[] if task.acceptance_criteria else [
                "acceptance criteria are required for precise local coverage"
            ],
        )

    def _template(
        self, text: str, task: AutomationTask
    ) -> tuple[str, list[dict[str, str]]]:
        subject = (
            task.impacted_components[0]
            if task.impacted_components else "the requested resource"
        )
        if any(word in text for word in ("invalid", "validation", "required")):
            outcome = f"the {subject} validation error is returned"
            return "common-negative-case", [
                self._scenario(
                    f"Invalid {subject} input", f"invalid {subject} input",
                    f"the client submits {subject}", outcome
                )
            ]
        if any(word in text for word in ("unauthor", "forbidden", "auth", "login")):
            return "authorization", [
                self._scenario(
                    f"Unauthorized access to {subject} is rejected",
                    "an unauthenticated client",
                    f"the client requests {subject}",
                    "the response is unauthorized",
                )
            ]
        if any(word in text for word in ("pagination", "filter", "sorting", "sort")):
            return "pagination-filtering-sorting", [
                self._scenario(
                    f"{subject} supports requested query controls",
                    f"{subject} data exists",
                    f"the client requests {subject} with query controls",
                    "the response contains requested result set",
                )
            ]
        if any(word in text for word in ("create", "post", "add")):
            return "crud-create", [
                self._scenario(
                    f"{subject} can be created", f"valid {subject} input",
                    f"the client creates {subject}", "the resource is created"
                )
            ]
        if any(word in text for word in ("delete", "remove")):
            return "crud-delete", [
                self._scenario(
                    f"{subject} can be deleted", f"an existing {subject}",
                    f"the client deletes {subject}",
                    "the resource is no longer available",
                )
            ]
        if any(word in text for word in ("update", "edit", "put", "patch")):
            return "crud-update", [
                self._scenario(
                    f"{subject} can be updated", f"an existing {subject}",
                    f"the client updates {subject}",
                    "the updated resource is returned",
                )
            ]
        if any(word in text for word in ("form", "submit")):
            return "form-submission", [
                self._scenario(
                    f"{subject} form can be submitted", f"a valid {subject} form",
                    "the user submits form", "the submission succeeds"
                )
            ]
        if any(word in text for word in ("navigation", "navigate", "page")):
            return "ui-navigation", [
                self._scenario(
                    f"{subject} can be reached", "an authenticated user",
                    f"the user navigates to {subject}",
                    f"the {subject} page is displayed",
                )
            ]
        return "api-contract", [
            self._scenario(
                f"{subject} API returns the expected contract",
                f"a valid {subject} request", f"the client requests {subject}",
                "the response matches the expected contract",
            )
        ]

    @staticmethod
    def _scenario(
        name: str, given: str, when: str, then: str
    ) -> dict[str, str]:
        return {"name": name, "given": given, "when": when, "then": then}

    @staticmethod
    def _tags(context: LocalContextBundle) -> str:
        if context.existing_feature_examples:
            for line in read_text(Path(context.existing_feature_examples[0])).splitlines():
                if line.startswith("@"):
                    return line.strip()
        return "@Regression"

    @staticmethod
    def _feature_name(task: AutomationTask) -> str:
        return task.impacted_components[0] if task.impacted_components else "Generated coverage"


class DeterministicTestSkeletonGenerator:
    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact:
        if "typescript" in task.target_test_type.lower():
            content = (
                "import { test, expect } from '@playwright/test';\n\n"
                f"test('{context.target_class_name}', async ({{ page }}) => {{\n"
                "  // TODO: implement only the locally unresolved interaction.\n"
                "});\n"
            )
        else:
            imports = "\n".join(
                f"import {item};" for item in sorted(set(context.required_imports))
            )
            content = (
                f"package {context.target_package};\n\n{imports}\n\n"
                f"public class {context.target_class_name} {{\n"
                '    @Given("a generated precondition")\n'
                "    public void aGeneratedPrecondition() {\n"
                "        // TODO: use an exact locally discovered agent method.\n"
                "    }\n\n"
                '    @When("the generated action is requested")\n'
                "    public void theGeneratedActionIsRequested() {\n"
                "        // TODO: implement the unresolved action.\n"
                "    }\n\n"
                '    @Then("the generated outcome is returned")\n'
                "    public void theGeneratedOutcomeIsReturned() {\n"
                "        // TODO: add assertions supported by local context.\n"
                "    }\n"
                "}\n"
            )
        return GeneratedArtifact(
            "test-skeleton", content, confidence_score=0.75,
            unresolved_gaps=["exact action mapping requires local evidence"],
        )


class DeterministicStepDefinitionGenerator:
    """Renders thin Cucumber glue only when a zero-argument local action exists."""

    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact:
        action_path, action_name = self._zero_argument_action(context)
        imports = sorted(set(context.required_imports))
        field, invocation = "", ""
        unresolved = ["no locally verified zero-argument action is available"]
        if action_path and action_name:
            qualified_name = context.available_agent_imports[action_path]
            agent_class = qualified_name.rsplit(".", 1)[-1]
            field_name = agent_class[:1].lower() + agent_class[1:]
            if "." in qualified_name:
                imports.append(qualified_name)
            field = (
                f"    private final {agent_class} {field_name};\n\n"
                f"    public {context.target_class_name}({agent_class} {field_name}) {{\n"
                f"        this.{field_name} = {field_name};\n"
                "    }\n\n"
            )
            invocation = f"        {field_name}.{action_name}();\n"
            unresolved = []
        rendered_imports = "\n".join(f"import {item};" for item in sorted(set(imports)))
        content = (
            f"package {context.target_package};\n\n{rendered_imports}\n\n"
            f"public class {context.target_class_name} {{\n"
            f"{field}"
            '    @Given("a generated precondition")\n'
            "    public void aGeneratedPrecondition() {\n"
            "        // The precondition is supplied by fixture or test data setup.\n"
            "    }\n\n"
            '    @When("the generated action is requested")\n'
            "    public void theGeneratedActionIsRequested() {\n"
            f"{invocation or '        // No verified local action is available.\\n'}"
            "    }\n\n"
            '    @Then("the generated outcome is returned")\n'
            "    public void theGeneratedOutcomeIsReturned() {\n"
            "        // Assertions are added only from locally verified evidence.\n"
            "    }\n"
            "}\n"
        )
        return GeneratedArtifact(
            "step-definition", content,
            confidence_score=0.88 if not unresolved else 0.5,
            unresolved_gaps=unresolved,
        )

    @staticmethod
    def _zero_argument_action(context: LocalContextBundle) -> tuple[str, str]:
        for path, methods in context.available_agent_methods.items():
            for method in methods:
                match = re.fullmatch(r"(\w+)\(\s*\)", method)
                if match:
                    return path, match.group(1)
        return "", ""


class DeterministicPageObjectGenerator:
    """Produces a framework-safe page-object shell without direct sample_mobiler access."""

    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact:
        component = (
            task.impacted_components[0] if task.impacted_components else "Generated"
        ).replace(" ", "")
        class_name = f"{component}Page"
        content = (
            f"package {context.target_package};\n\n"
            "import lombok.extern.slf4j.Slf4j;\n\n"
            "@Slf4j\n"
            f"public class {class_name} {{\n"
            "    public void waitUntilReady() {\n"
            "        // Add only locally verified WebAction interactions.\n"
            "    }\n"
            "}\n"
        )
        return GeneratedArtifact(
            "page-object", content, confidence_score=0.6,
            unresolved_gaps=["no locally verified WebAction method is available"],
        )


class DeterministicReviewEngine:
    """Reports deterministic policy violations before any model-based review."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def review(self, context: LocalContextBundle) -> GeneratedArtifact:
        findings: list[str] = []
        paths = list(
            dict.fromkeys(
                context.relevant_files + context.available_agents + context.page_objects
            )
        )
        for relative_path in paths:
            context_path = Path(relative_path)
            content = read_text(self.root / context_path)
            if not content:
                continue
            if "Thread.sleep(" in content:
                findings.append(f"BLOCKER {relative_path}: Thread.sleep is prohibited")
            if (
                ("page" in context_path.stem.lower() or "screen" in context_path.stem.lower())
                and "class " in content
                and "@Slf4j" not in content
            ):
                findings.append(
                    f"BLOCKER {relative_path}: page or screen object is missing @Slf4j"
                )
        for pattern in context.known_step_patterns:
            if context.known_step_patterns.count(pattern) > 1:
                findings.append(f"BLOCKER duplicate Cucumber step pattern: {pattern}")
        content = "\n".join(dict.fromkeys(findings)) or "No deterministic review findings."
        return GeneratedArtifact("review-report", content + "\n", confidence_score=0.95)
