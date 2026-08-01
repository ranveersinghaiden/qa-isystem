#!/usr/bin/env python3
"""Compatibility facade and CLI for the composable local QA engine."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from qa_engine_context import LocalContextBuilder, read_text, words
from qa_engine_generators import (
    DeterministicBddGenerator,
    DeterministicPageObjectGenerator,
    DeterministicReviewEngine,
    DeterministicStepDefinitionGenerator,
    DeterministicTestSkeletonGenerator,
)
from qa_engine_models import (
    AutomationTask,
    ContextBudget,
    GeneratedArtifact,
    GenerationMode,
    GenerationPlan,
    LlmGenerationRequest,
    LlmGenerationResponse,
    LlmProvider,
    LocalContextBundle,
    RepairType,
    RunObservability,
    TaskType,
    TestDomain,
    ValidationResult,
    to_jsonable,
)
from qa_engine_packs import GenerationPack, PackRegistry, PackSelection, default_pack_registry
from qa_engine_repair import LocalRepairEngine
from qa_engine_router import GenerationRouter
from qa_engine_validators import (
    BuildValidator,
    ConventionValidator,
    DuplicateScenarioValidator,
    FormatLintValidator,
    LocalValidators,
    PageObjectValidator,
    StepDefinitionValidator,
    SymbolValidator,
    SyntaxValidator,
)

# Private aliases retain compatibility for callers that imported old helpers.
_read = read_text
_words = words


def _task_from_json(payload: dict[str, Any]) -> AutomationTask:
    if not isinstance(payload, dict):
        raise TypeError("AutomationTask JSON must be an object")
    payload = dict(payload)
    payload["task_type"] = TaskType(payload["task_type"])
    if "test_domain" in payload and payload["test_domain"] is not None:
        try:
            payload["test_domain"] = TestDomain(str(payload["test_domain"]).lower())
        except ValueError:
            # Preserve unknown JSON input for registry's safe BDD fallback.
            payload["test_domain"] = str(payload["test_domain"])
    for field in ("impacted_components", "acceptance_criteria"):
        value = payload.get(field, [])
        if not isinstance(value, list) or not all(
            isinstance(item, str) for item in value
        ):
            raise ValueError(f"{field} must be a list of strings")
    return AutomationTask(**payload)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run local-first QA generation and emit a structured JSON contract."
    )
    parser.add_argument("--task", required=True, help="Path to AutomationTask JSON.")
    parser.add_argument("--repo", default=".", help="Repository root.")
    parser.add_argument("--output", help="JSON output path; stdout when omitted.")
    args = parser.parse_args()
    try:
        task = _task_from_json(
            json.loads(Path(args.task).read_text(encoding="utf-8"))
        )
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
        print(f"Invalid AutomationTask JSON: {exc}", file=sys.stderr)
        return 2
    result = GenerationRouter(LocalContextBuilder(Path(args.repo))).route(task)
    output = json.dumps(result, indent=2, ensure_ascii=True)
    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
