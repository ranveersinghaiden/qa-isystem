from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import gather_context as context

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "gather_context.py"


def test_infer_product_from_paths_uses_majority_and_stable_tie_break() -> None:
    assert context.infer_product_from_paths(["web/a.java", "web/b.java", "api/c.java"]) == "web"
    assert context.infer_product_from_paths(["mobile/a.java", "web/b.java"]) == "mobile"
    assert context.infer_product_from_paths(["other/a.java"]) == ""


def test_resolve_product_target_reads_only_local_catalog(tmp_path: Path) -> None:
    catalog = tmp_path / "catalog.json"
    catalog.write_text(
        '{"products":{"mobile":{"surface":"mobile","modules":["mobile/tests"],"directory_keyword":"mobile"}}}',
        encoding="utf-8",
    )
    target = context.resolve_product_target("mobile", str(catalog))
    assert target["surface"] == "mobile"
    assert target["modules"] == ["mobile/tests"]


def test_build_context_includes_only_supplied_local_sections() -> None:
    rendered = context.build_context(
        product="web",
        target={"surface": "web", "modules": ["web/tests"]},
        task_description="Validate local scenarios.",
        logic_change="Add coverage.",
    )
    assert "## Task" in rendered
    assert "## Logic change" in rendered
    assert "Previous attempt" not in rendered


def test_cli_writes_local_context(tmp_path: Path) -> None:
    catalog = tmp_path / "catalog.json"
    output = tmp_path / "context.md"
    catalog.write_text(
        '{"products":{"web":{"surface":"web","modules":["web/tests"]}}}',
        encoding="utf-8",
    )
    result = subprocess.run(
        [
            sys.executable, str(SCRIPT_PATH), "--catalog", str(catalog),
            "--product", "web", "--task-description", "Validate locally.",
            "--output", str(output),
        ],
        capture_output=True, text=True, check=False,
    )
    assert result.returncode == 0
    assert "Validate locally." in output.read_text(encoding="utf-8")
