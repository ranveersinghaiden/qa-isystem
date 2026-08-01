"""Bounded local repository evidence collection for QA generation."""

from __future__ import annotations

import re
from pathlib import Path

from qa_engine_models import AutomationTask, ContextBudget, LocalContextBundle


def words(value: str) -> set[str]:
    normalized = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", value)
    return {
        word.lower()
        for word in re.findall(r"[A-Za-z][A-Za-z0-9_]{2,}", normalized)
    }


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


class LocalContextBuilder:
    """Ranks local evidence with transparent token overlap and path affinity."""

    SOURCE_SUFFIXES = {".java", ".ts", ".tsx", ".js", ".feature", ".yaml", ".yml", ".json"}
    NOISE_PARTS = {"target", "node_modules", ".git", "dist", "build", ".idea"}

    def __init__(self, repository_root: Path, budget: ContextBudget | None = None) -> None:
        self.root = repository_root.resolve()
        self.budget = budget or ContextBudget()

    def build(self, task: AutomationTask) -> LocalContextBundle:
        changed = self._changed_files(task)
        vocabulary = words(
            " ".join(task.impacted_components + task.acceptance_criteria)
            + " "
            + task.pr_diff_summary
        )
        vocabulary.update(self._graphify_terms(task))
        candidates = self._candidates()
        ranked = sorted(
            ((self._score(path, changed, vocabulary), path) for path in candidates),
            key=lambda item: (-item[0], str(item[1])),
        )
        selected = [path for score, path in ranked if score > 0][: self.budget.max_files]
        if not selected:
            selected = [path for _, path in ranked[: self.budget.max_files]]
        bundle = LocalContextBundle(
            relevant_files=[str(path.relative_to(self.root)) for path in selected]
        )
        bundle.relevant_file_snippets = self._snippets(selected, vocabulary)
        feature_paths = [path for path in candidates if path.suffix == ".feature"]
        step_paths = [
            path for path in candidates
            if "step" in path.name.lower() and path.suffix == ".java"
        ]
        agent_paths = [
            path for path in candidates
            if path.suffix == ".java"
            and any(
                part in str(path).lower()
                for part in ("pageobject", "actions", "support", "agent")
            )
        ]
        fixture_paths = [
            path for path in candidates
            if any(token in path.name.lower() for token in ("fixture", "data", "builder"))
        ]
        bundle.existing_feature_examples = [
            self._relative(path)
            for path in self._rank_paths(
                feature_paths, vocabulary, self.budget.max_example_test_files
            )
        ]
        bundle.existing_step_definitions = [
            self._relative(path)
            for path in self._rank_paths(step_paths, vocabulary, self.budget.max_files)
        ]
        agents = self._rank_paths(agent_paths, vocabulary, self.budget.max_agents)
        bundle.available_agents = [self._relative(path) for path in agents]
        bundle.available_agent_methods = {
            self._relative(path): self._public_methods(read_text(path))
            for path in agents
        }
        bundle.available_agent_imports = {
            self._relative(path): self._qualified_class_name(path)
            for path in agents
        }
        bundle.known_step_patterns = [
            pattern for path in step_paths
            for pattern in self._cucumber_step_patterns(read_text(path))
        ][:50]
        bundle.fixtures = [
            self._relative(path)
            for path in self._rank_paths(
                fixture_paths, vocabulary, self.budget.max_fixtures
            )
        ]
        bundle.page_objects = [
            self._relative(path) for path in agent_paths
            if "pageobject" in str(path).lower()
        ][: self.budget.max_agents]
        bundle.endpoints = self._endpoints(selected)
        bundle.local_selectors = self._selectors(selected)
        bundle.api_operations = self._api_operations(selected)
        self._set_target(bundle, task)
        bundle.template_match = (
            bundle.existing_feature_examples[0]
            if bundle.existing_feature_examples else ""
        )
        evidence = (
            len(bundle.existing_feature_examples)
            + len(bundle.existing_step_definitions)
            + len(bundle.available_agents)
        )
        bundle.confidence_score = min(
            1.0, 0.35 + evidence * 0.12 + (0.2 if task.acceptance_criteria else 0.0)
        )
        return bundle

    def _changed_files(self, task: AutomationTask) -> set[str]:
        return {path for path in task.changed_files if path}

    def _graphify_terms(self, task: AutomationTask) -> set[str]:
        report = self.root / "graphify-out" / "GRAPH_REPORT.md"
        if not report.is_file() or not task.impacted_components:
            return set()
        component_terms = words(" ".join(task.impacted_components))
        text = read_text(report)[:20_000]
        return words(" ".join(
            line for line in text.splitlines()
            if component_terms.intersection(words(line))
        ))

    def _candidates(self) -> list[Path]:
        return [
            path for path in self.root.rglob("*")
            if path.is_file()
            and path.suffix in self.SOURCE_SUFFIXES
            and not any(part in self.NOISE_PARTS for part in path.parts)
        ]

    def _score(self, path: Path, changed: set[str], vocabulary: set[str]) -> int:
        relative = self._relative(path)
        score = 20 if relative in changed else 0
        score += len(vocabulary.intersection(words(relative))) * 4
        if path.suffix == ".feature":
            score += 2
        if "step" in path.name.lower():
            score += 2
        return score

    def _rank_paths(
        self, paths: list[Path], vocabulary: set[str], limit: int
    ) -> list[Path]:
        return [
            path for _, path in sorted(
                ((self._score(path, set(), vocabulary), path) for path in paths),
                key=lambda item: (-item[0], str(item[1])),
            )[:limit]
        ]

    def _snippets(
        self, paths: list[Path], vocabulary: set[str]
    ) -> dict[str, list[str]]:
        remaining = self.budget.max_total_chars
        snippets: dict[str, list[str]] = {}
        for path in paths:
            if remaining <= 0:
                break
            lines = read_text(path).splitlines()
            matching = [
                line.strip() for line in lines
                if vocabulary.intersection(words(line))
            ]
            chosen = (
                matching or [line.strip() for line in lines if line.strip()]
            )[: self.budget.max_snippets_per_file]
            clipped = [line[:min(800, remaining)] for line in chosen]
            remaining -= sum(len(line) for line in clipped)
            snippets[self._relative(path)] = clipped
        return snippets

    @staticmethod
    def _public_methods(content: str) -> list[str]:
        pattern = re.compile(
            r"public\s+(?:static\s+)?[\w<>\[\], ?]+\s+(\w+)\s*\(([^)]*)\)"
        )
        return [f"{name}({args.strip()})" for name, args in pattern.findall(content)][:20]

    @staticmethod
    def _cucumber_step_patterns(content: str) -> list[str]:
        return re.findall(r'@(?:Given|When|Then)\s*\(\s*"([^"]+)"\s*\)', content)

    @staticmethod
    def _qualified_class_name(path: Path) -> str:
        package = re.search(
            r"^\s*package\s+([\w.]+);", read_text(path), re.MULTILINE
        )
        return f"{package.group(1)}.{path.stem}" if package else path.stem

    @staticmethod
    def _endpoints(paths: list[Path]) -> list[str]:
        pattern = re.compile(
            r"""(?:@(?:Get|Post|Put|Delete|Request)Mapping\s*\(\s*["']([^"']+)|
            \b(?:GET|POST|PUT|DELETE)\s+(/[A-Za-z0-9_/{}/.-]+))""",
            re.VERBOSE,
        )
        return list(dict.fromkeys(
            match[0] or match[1]
            for path in paths for match in pattern.findall(read_text(path))
        ))[:10]

    @staticmethod
    def _selectors(paths: list[Path]) -> list[str]:
        pattern = re.compile(
            r"""(?:
                (?:data-testid|testId|accessibility(?:\s|-)?id)\s*[:=(]\s*["']([^"']+)|
                (?:getByTestId|accessibilityId)\s*\(\s*["']([^"']+)
            )""",
            re.IGNORECASE | re.VERBOSE,
        )
        return list(dict.fromkeys(
            match[0] or match[1]
            for path in paths
            for match in pattern.findall(read_text(path))
        ))[:10]

    @staticmethod
    def _api_operations(paths: list[Path]) -> list[str]:
        pattern = re.compile(r"\b(GET|POST|PUT|DELETE|PATCH)\b", re.IGNORECASE)
        return list(dict.fromkeys(
            operation.upper()
            for path in paths
            for operation in pattern.findall(read_text(path))
        ))[:10]

    def _set_target(
        self, bundle: LocalContextBundle, task: AutomationTask
    ) -> None:
        java = "typescript" not in task.target_test_type.lower()
        module = next((
            part for part in (
                "mobile-qa_tooling", "web_suite/web-automation",
                "customer_portal/customer_portal-e2e-tests", "customer_portal/mobile_app-web-automation",
            ) if part in " ".join(bundle.relevant_files)
        ), "")
        component = (
            task.impacted_components[0] if task.impacted_components else "Generated"
        ).replace(" ", "")
        if java:
            template = (
                self.root / bundle.existing_step_definitions[0]
                if bundle.existing_step_definitions else None
            )
            template_text = read_text(template) if template else ""
            package = re.search(
                r"^\s*package\s+([\w.]+);", template_text, re.MULTILINE
            )
            imports = re.findall(
                r"^\s*import\s+([\w.]+);", template_text, re.MULTILINE
            )
            root = (
                str(template.parent.relative_to(self.root)) if template
                else f"{module}/src/test/java" if module else "src/test/java"
            )
            bundle.target_package = (
                package.group(1) if package else "com.example.stepDefinition.generated"
            )
            bundle.target_class_name = f"{component}Steps"
            bundle.target_file_path = f"{root}/{bundle.target_class_name}.java"
            cucumber = [
                "io.cucumber.java.en.Given", "io.cucumber.java.en.Then",
                "io.cucumber.java.en.When",
            ]
            bundle.required_imports = list(dict.fromkeys(
                cucumber + [item for item in imports if item.startswith("org.junit.")]
            ))
            bundle.test_framework, bundle.build_tool = "Cucumber Java", "Maven"
        else:
            bundle.target_class_name = f"{component}.spec"
            bundle.target_file_path = f"src/{bundle.target_class_name}.ts"
            bundle.required_imports = ["@playwright/test"]
            bundle.test_framework, bundle.build_tool = "Playwright", "npm"
        bundle.conventions = [
            "reuse existing step phrases", "one Given, one When, one Then",
            "no direct sample_mobiler calls in step definitions",
        ]
        if (self.root / "graphify-out" / "GRAPH_REPORT.md").is_file():
            bundle.conventions.append(
                "Graphify community terms were used for local ranking"
            )

    def _relative(self, path: Path) -> str:
        return str(path.relative_to(self.root))
