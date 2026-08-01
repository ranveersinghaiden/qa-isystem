#!/usr/bin/env python3
"""Build deterministic context from repository-local, synthetic metadata."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

CATALOG = "knowledge/catalog.json"
PRODUCT_PATHS: list[tuple[str, re.Pattern[str]]] = [
    ("mobile", re.compile(r"(^|/)mobile/", re.IGNORECASE)),
    ("web", re.compile(r"(^|/)web/", re.IGNORECASE)),
    ("api", re.compile(r"(^|/)api/", re.IGNORECASE)),
]


def _load_catalog(path: str = CATALOG) -> dict[str, dict[str, object]]:
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    products = data.get("products", {})
    return products if isinstance(products, dict) else {}


def infer_product_from_paths(paths: list[str]) -> str:
    scores: dict[str, int] = {}
    for path in paths:
        for name, pattern in PRODUCT_PATHS:
            if pattern.search(path):
                scores[name] = scores.get(name, 0) + 1
                break
    if not scores:
        return ""
    return next(
        name for name, _ in PRODUCT_PATHS if scores.get(name) == max(scores.values())
    )


def resolve_product_target(product: str, catalog_path: str = CATALOG) -> dict[str, object]:
    entry = _load_catalog(catalog_path).get(product, {})
    if not isinstance(entry, dict):
        entry = {}
    modules = entry.get("modules", [])
    if not isinstance(modules, list) or not all(isinstance(item, str) for item in modules):
        modules = []
    return {
        "product": product,
        "modules": modules,
        "surface": entry.get("surface", "web") if isinstance(entry.get("surface", "web"), str) else "web",
        "directory_keyword": entry.get("directory_keyword", "") if isinstance(entry.get("directory_keyword", ""), str) else "",
    }


def resolve_agent_context(product: str, target: dict[str, object] | None) -> dict[str, str]:
    surface = str((target or {}).get("surface", ""))
    if not surface:
        surface = "mobile" if product == "mobile" else "web"
    return {"surface": surface, "label": product}


def read_maybe_file(value: str) -> str:
    if not value.startswith("@"):
        return value
    try:
        return Path(value[1:]).read_text(encoding="utf-8")
    except OSError:
        return ""


def build_context(
    *,
    product: str,
    target: dict[str, object],
    task_description: str,
    logic_change: str = "",
    previous_attempt: str = "",
    review_notes: str = "",
) -> str:
    modules = ", ".join(target["modules"]) or "(none)"
    sections = [
        "# Local validation context",
        f"- Product: {product}",
        f"- Surface: {target['surface']}",
        f"- Modules: {modules}",
        "## Task",
        task_description,
    ]
    for heading, value in (
        ("Logic change", logic_change),
        ("Previous attempt", previous_attempt),
        ("Review notes", review_notes),
    ):
        if value.strip():
            sections.extend((f"## {heading}", value.strip()))
    return "\n".join(sections) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Build local validation context.")
    parser.add_argument("--product", default="")
    parser.add_argument("--infer-product-from", default="")
    parser.add_argument("--catalog", default=CATALOG)
    parser.add_argument("--task-description", default="")
    parser.add_argument("--logic-change", default="")
    parser.add_argument("--previous-attempt", default="")
    parser.add_argument("--review-notes", default="")
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    product = args.product
    if args.infer_product_from:
        product = infer_product_from_paths(read_maybe_file(f"@{args.infer_product_from}").splitlines())
        if not product:
            return 3
        print(product)
        return 0
    if not product or not args.task_description:
        parser.error("--product and --task-description are required")
    target = resolve_product_target(product, args.catalog)
    if not target["modules"]:
        print(f"Unknown product: {product}", file=sys.stderr)
        return 2
    context = build_context(
        product=product,
        target=target,
        task_description=read_maybe_file(args.task_description),
        logic_change=read_maybe_file(args.logic_change),
        previous_attempt=read_maybe_file(args.previous_attempt),
        review_notes=read_maybe_file(args.review_notes),
    )
    if args.output:
        Path(args.output).write_text(context, encoding="utf-8")
    else:
        print(context, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
