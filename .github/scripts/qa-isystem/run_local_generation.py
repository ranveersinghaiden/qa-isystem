#!/usr/bin/env python3
"""Create and route a workflow/local-development AutomationTask."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path

from local_generation import (
    AutomationTask,
    GenerationRouter,
    LocalContextBuilder,
    TaskType,
    to_jsonable,
)


def _read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8", errors="replace") if path else ""


def _acceptance_criteria(requirements: str) -> list[str]:
    """Keep locally supplied requirement lines bounded."""
    candidates = [
        line.strip(" -*\t")
        for line in requirements.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    return candidates[:12]


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the local-first generation contract.")
    parser.add_argument("--repo", default=".")
    parser.add_argument("--task-type", required=True, choices=[item.value for item in TaskType])
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--product-context", default="")
    parser.add_argument("--requirements-file", default="")
    parser.add_argument("--diff-summary", default="")
    parser.add_argument("--changed-file", action="append", default=[])
    parser.add_argument("--impacted-component", action="append", default=[])
    parser.add_argument("--target-test-type", default="java-bdd")
    parser.add_argument("--attempt-number", type=int, default=1)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    requirements = _read(args.requirements_file)
    task = AutomationTask(
        task_id=args.task_id,
        task_type=TaskType(args.task_type),
        product_context=args.product_context,
        pr_diff_summary=args.diff_summary,
        changed_files=args.changed_file,
        impacted_components=args.impacted_component,
        acceptance_criteria=_acceptance_criteria(requirements),
        target_test_type=args.target_test_type,
        attempt_number=args.attempt_number,
        created_at=datetime.now(UTC).isoformat(),
    )
    result = GenerationRouter(LocalContextBuilder(Path(args.repo))).route(task)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(
            {"engine_version": "1.0", "task": to_jsonable(task), "result": result},
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
