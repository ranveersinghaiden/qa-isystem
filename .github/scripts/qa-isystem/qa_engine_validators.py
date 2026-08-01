"""Local validation stages for generated QA artifacts."""

from __future__ import annotations

import difflib
import re
import subprocess
from typing import TYPE_CHECKING

from qa_engine_context import LocalContextBuilder
from qa_engine_models import (
    GeneratedArtifact,
    LocalContextBundle,
    RepairType,
    ValidationResult,
)

if TYPE_CHECKING:
    from qa_engine_packs import GenerationPack


class SymbolValidator:
    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        missing: list[str] = []
        if artifact.kind == "test-skeleton":
            for imported in re.findall(
                r"^import\s+([\w.]+);", artifact.content, re.MULTILINE
            ):
                simple_name = imported.rsplit(".", 1)[-1]
                if (
                    imported not in context.required_imports
                    and not any(
                        simple_name in path
                        for path in context.relevant_files + context.available_agents
                    )
                ):
                    missing.append(imported)
        return ValidationResult(
            not missing,
            ["referenced imports are not available in local context"] if missing else [],
            missing_symbols=missing,
            suggested_repair_type=RepairType.CHEAP_LLM if missing else RepairType.LOCAL_FIX,
        )


class DuplicateScenarioValidator:
    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        names = [scenario["name"].lower() for scenario in artifact.scenarios]
        duplicates = list(dict.fromkeys(
            name for name in names if names.count(name) > 1
        ))
        known_names = {
            name.lower()
            for snippets in context.relevant_file_snippets.values()
            for name in re.findall(
                r"^\s*Scenario(?: Outline)?:\s*(.+)$",
                "\n".join(snippets),
                re.MULTILINE,
            )
        }
        near_duplicates: list[str] = []
        for name in names:
            for known in known_names | set(names):
                if known == name:
                    continue
                similarity = difflib.SequenceMatcher(
                    None, re.sub(r"\W+", " ", name), re.sub(r"\W+", " ", known)
                ).ratio()
                if similarity >= 0.88:
                    near_duplicates.append(f"{name} ~= {known}")
        duplicates.extend(item for item in near_duplicates if item not in duplicates)
        return ValidationResult(
            not duplicates,
            ["duplicate or near-duplicate BDD scenario"] if duplicates else [],
            duplicate_definitions=duplicates,
            suggested_repair_type=(
                RepairType.HUMAN_REVIEW if near_duplicates else RepairType.LOCAL_FIX
            ),
        )


class StepDefinitionValidator:
    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        if artifact.kind != "step-definition":
            return ValidationResult(True)
        patterns = LocalContextBuilder._cucumber_step_patterns(artifact.content)
        duplicates = [
            pattern for pattern in patterns if pattern in context.known_step_patterns
        ]
        errors = [f"duplicate Cucumber step pattern: {pattern}" for pattern in duplicates]
        return ValidationResult(
            not errors,
            errors,
            duplicate_definitions=duplicates,
            suggested_repair_type=(
                RepairType.HUMAN_REVIEW if errors else RepairType.LOCAL_FIX
            ),
        )


class PageObjectValidator:
    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        if artifact.kind != "page-object":
            return ValidationResult(True)
        errors: list[str] = []
        if "@Slf4j" not in artifact.content:
            errors.append("page object must be annotated with @Slf4j")
        if re.search(r"\b(?:page|sample_mobiler)\s*\.", artifact.content):
            errors.append(
                "page object must use a local action abstraction, not direct sample_mobiler access"
            )
        return ValidationResult(
            not errors,
            errors,
            suggested_repair_type=(
                RepairType.HUMAN_REVIEW if errors else RepairType.LOCAL_FIX
            ),
        )


class ConventionValidator:
    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        errors: list[str] = []
        if artifact.kind == "gherkin":
            scenario_blocks = re.split(
                r"(?m)^\s*Scenario(?: Outline)?:[^\n]*\n", artifact.content
            )[1:]
            scenario_pattern = re.compile(
                r"(?m)^\s*Given .+\n(?:\s*And .+\n)*"
                r"\s*When .+\n(?:\s*And .+\n)*"
                r"\s*Then .+(?:\n\s*And .+)*"
            )
            if not scenario_blocks or any(
                not scenario_pattern.search(block)
                or len(re.findall(r"(?m)^\s*Given .+", block)) != 1
                or len(re.findall(r"(?m)^\s*When .+", block)) != 1
                or len(re.findall(r"(?m)^\s*Then .+", block)) != 1
                for block in scenario_blocks
            ):
                errors.append("each scenario must contain one Given, When, and Then")
        if (
            artifact.kind == "test-skeleton"
            and context.target_file_path
            and "typescript" not in context.target_file_path
            and not artifact.content.startswith(f"package {context.target_package};")
        ):
            errors.append("package declaration does not match target")
        if re.search(r"\bThread\s*\.\s*sleep\s*\(", artifact.content):
            errors.append("Thread.sleep is prohibited")
        return ValidationResult(not errors, errors)


class SyntaxValidator:
    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        errors = (
            ["unbalanced syntax delimiters"]
            if artifact.kind == "test-skeleton"
            and artifact.content.count("{") != artifact.content.count("}")
            else []
        )
        return ValidationResult(not errors, errors)


class BuildValidator:
    """Runs an explicitly configured, targeted command without shell interpolation."""

    def __init__(
        self, command: list[str] | None = None, timeout_seconds: int = 120
    ) -> None:
        self.command, self.timeout_seconds = command, timeout_seconds

    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        if not self.command:
            return ValidationResult(True, compilation_result="not-configured")
        try:
            result = subprocess.run(
                self.command,
                check=False,
                capture_output=True,
                text=True,
                timeout=self.timeout_seconds,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            return ValidationResult(
                False,
                [f"targeted build could not run: {exc}"],
                compilation_result="failed",
                suggested_repair_type=RepairType.HUMAN_REVIEW,
            )
        return ValidationResult(
            result.returncode == 0,
            [] if result.returncode == 0 else [
                result.stderr[-2000:] or "targeted build failed"
            ],
            compilation_result="passed" if result.returncode == 0 else "failed",
        )


class FormatLintValidator(BuildValidator):
    def validate(
        self, artifact: GeneratedArtifact, context: LocalContextBundle
    ) -> ValidationResult:
        result = super().validate(artifact, context)
        result.lint_result, result.compilation_result = (
            result.compilation_result,
            "not-run",
        )
        return result


class LocalValidators:
    def __init__(
        self,
        build: BuildValidator | None = None,
        lint: FormatLintValidator | None = None,
    ) -> None:
        self.validators = [
            SymbolValidator(),
            DuplicateScenarioValidator(),
            StepDefinitionValidator(),
            PageObjectValidator(),
            ConventionValidator(),
            SyntaxValidator(),
            build or BuildValidator(),
            lint or FormatLintValidator(),
        ]

    def validate(
        self,
        artifact: GeneratedArtifact,
        context: LocalContextBundle,
        pack: GenerationPack | None = None,
    ) -> ValidationResult:
        results = [
            validator.validate(artifact, context) for validator in self.validators
        ]
        if pack is not None:
            results.append(pack.validate(artifact, context))
        errors = [error for result in results for error in result.errors]
        warnings = [
            warning for result in results for warning in result.warnings
        ] + artifact.unresolved_gaps
        missing = [
            item for result in results for item in result.missing_symbols
        ]
        duplicates = [
            item for result in results for item in result.duplicate_definitions
        ]
        repair = (
            RepairType.LOCAL_FIX if not errors else (
                RepairType.CHEAP_LLM if missing else RepairType.HUMAN_REVIEW
            )
        )
        return ValidationResult(
            not errors,
            errors,
            warnings,
            missing,
            duplicates,
            next(
                (
                    result.compilation_result for result in results
                    if result.compilation_result != "not-run"
                ),
                "not-run",
            ),
            next(
                (
                    result.lint_result for result in results
                    if result.lint_result != "not-run"
                ),
                "not-run",
            ),
            repair,
        )
