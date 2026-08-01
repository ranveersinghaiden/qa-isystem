"""BDD pack: Gherkin generation plus tag policy."""

from __future__ import annotations

import re

from qa_engine_generators import (
    DeterministicBddGenerator,
    DeterministicPageObjectGenerator,
    DeterministicStepDefinitionGenerator,
    DeterministicTestSkeletonGenerator,
)
from qa_engine_models import (
    AutomationTask,
    GeneratedArtifact,
    LocalContextBundle,
    TaskType,
    TestDomain,
    ValidationResult,
)


class BddPack:
    domain = TestDomain.BDD
    policy_rules = ("gherkin", "duplicate-scenarios", "cucumber-step-patterns", "tags")

    def __init__(self) -> None:
        self.generator = DeterministicBddGenerator()
        self.skeleton = DeterministicTestSkeletonGenerator()
        self.steps = DeterministicStepDefinitionGenerator()
        self.page_objects = DeterministicPageObjectGenerator()

    def generate(
        self, task: AutomationTask, context: LocalContextBundle
    ) -> GeneratedArtifact:
        if task.task_type == TaskType.STEP_DEFINITION_GENERATION:
            return self.steps.generate(task, context)
        if task.task_type == TaskType.PAGE_OBJECT_GENERATION:
            return self.page_objects.generate(task, context)
        if task.task_type in {
            TaskType.TEST_SKELETON_GENERATION,
            TaskType.TEST_GAP_GENERATION,
            TaskType.TEST_FIX,
        }:
            return self.skeleton.generate(task, context)
        return self.generator.generate(task, context)

    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        if artifact.kind != "gherkin":
            return ValidationResult(True)
        has_tag = bool(
            re.match(
                r"\s*@[A-Za-z][\w-]*(?:\s+@[A-Za-z][\w-]*)*\s*\n"
                r"\s*Feature\s*:",
                artifact.content,
            )
        )
        return ValidationResult(
            has_tag,
            errors=[] if has_tag else [
                "BDD gherkin features require at least one leading tag before Feature"
            ],
        )
