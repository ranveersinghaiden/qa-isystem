"""Deterministic repairs that do not require model reasoning."""

from __future__ import annotations

from qa_engine_models import GeneratedArtifact, LocalContextBundle, ValidationResult


class LocalRepairEngine:
    def repair(
        self,
        artifact: GeneratedArtifact,
        context: LocalContextBundle,
        validation: ValidationResult,
    ) -> GeneratedArtifact:
        content = artifact.content
        if artifact.kind == "test-skeleton":
            seen: set[str] = set()
            lines: list[str] = []
            for line in content.splitlines():
                if line.startswith("import ") and line in seen:
                    continue
                seen.add(line)
                lines.append(line)
            content = "\n".join(lines) + "\n"
        if artifact.kind == "gherkin":
            unique, seen = [], set()
            for scenario in artifact.scenarios:
                key = scenario["name"].lower()
                if key not in seen:
                    seen.add(key)
                    unique.append(scenario)
            artifact.scenarios = unique
        artifact.content = content
        return artifact
