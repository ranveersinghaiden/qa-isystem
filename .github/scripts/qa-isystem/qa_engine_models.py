"""Shared contracts and provider adapters for the local QA engine."""

from __future__ import annotations

import dataclasses
import enum
import re
from typing import Any, Final, Protocol


JAVA_KEYWORDS: Final[frozenset[str]] = frozenset(
    {
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "_", "true",
        "false", "null", "exports", "module", "non-sealed", "open",
        "opens", "permits", "provides", "record", "requires", "sealed",
        "to", "transitive", "uses", "var", "with", "yield",
    }
)


def java_pascal_identifier(value: str) -> str:
    parts = re.findall(r"[A-Za-z0-9]+", value)
    if not parts:
        return "Generated"
    identifier = "".join(part[:1].upper() + part[1:].lower() for part in parts)
    if identifier[0].isdigit() or identifier.lower() in JAVA_KEYWORDS:
        return f"Generated{identifier}"
    return identifier


class TaskType(str, enum.Enum):
    BDD_GENERATION = "BDD_GENERATION"
    TEST_SKELETON_GENERATION = "TEST_SKELETON_GENERATION"
    STEP_DEFINITION_GENERATION = "STEP_DEFINITION_GENERATION"
    PAGE_OBJECT_GENERATION = "PAGE_OBJECT_GENERATION"
    TEST_GAP_GENERATION = "TEST_GAP_GENERATION"
    TEST_FIX = "TEST_FIX"
    VALIDATION_ONLY = "VALIDATION_ONLY"
    WEB_TEST_GENERATION = "WEB_TEST_GENERATION"
    API_TEST_GENERATION = "API_TEST_GENERATION"
    MOBILE_TEST_GENERATION = "MOBILE_TEST_GENERATION"


class TestDomain(str, enum.Enum):
    BDD = "bdd"
    WEB = "web"
    API = "api"
    MOBILE = "mobile"


class GenerationMode(str, enum.Enum):
    DETERMINISTIC = "DETERMINISTIC"
    CHEAP_LLM = "CHEAP_LLM"
    PREMIUM_LLM = "PREMIUM_LLM"
    HUMAN_REVIEW = "HUMAN_REVIEW"


class RepairType(str, enum.Enum):
    LOCAL_FIX = "LOCAL_FIX"
    CHEAP_LLM = "CHEAP_LLM"
    PREMIUM_LLM = "PREMIUM_LLM"
    HUMAN_REVIEW = "HUMAN_REVIEW"


@dataclasses.dataclass
class AutomationTask:
    task_id: str
    task_type: TaskType
    product_context: str = ""
    pr_diff_summary: str = ""
    changed_files: list[str] = dataclasses.field(default_factory=list)
    impacted_components: list[str] = dataclasses.field(default_factory=list)
    acceptance_criteria: list[str] = dataclasses.field(default_factory=list)
    target_test_type: str = "java-bdd"
    attempt_number: int = 1
    created_at: str = ""
    test_domain: TestDomain | str | None = None


@dataclasses.dataclass
class ContextBudget:
    max_files: int = 8
    max_snippets_per_file: int = 3
    max_total_chars: int = 18_000
    max_example_test_files: int = 2
    max_agents: int = 3
    max_fixtures: int = 3


@dataclasses.dataclass
class LocalContextBundle:
    relevant_files: list[str] = dataclasses.field(default_factory=list)
    relevant_file_snippets: dict[str, list[str]] = dataclasses.field(default_factory=dict)
    existing_feature_examples: list[str] = dataclasses.field(default_factory=list)
    existing_step_definitions: list[str] = dataclasses.field(default_factory=list)
    available_agents: list[str] = dataclasses.field(default_factory=list)
    available_agent_methods: dict[str, list[str]] = dataclasses.field(default_factory=dict)
    available_agent_imports: dict[str, str] = dataclasses.field(default_factory=dict)
    known_step_patterns: list[str] = dataclasses.field(default_factory=list)
    fixtures: list[str] = dataclasses.field(default_factory=list)
    page_objects: list[str] = dataclasses.field(default_factory=list)
    endpoints: list[str] = dataclasses.field(default_factory=list)
    local_selectors: list[str] = dataclasses.field(default_factory=list)
    api_operations: list[str] = dataclasses.field(default_factory=list)
    target_package: str = ""
    target_file_path: str = ""
    target_class_name: str = ""
    required_imports: list[str] = dataclasses.field(default_factory=list)
    test_framework: str = ""
    build_tool: str = ""
    conventions: list[str] = dataclasses.field(default_factory=list)
    template_match: str = ""
    confidence_score: float = 0.0
    selected_test_domain: str = ""
    pack_inference: str = ""


@dataclasses.dataclass
class GenerationPlan:
    generation_mode: GenerationMode
    reason: str
    selected_template: str = ""
    required_missing_items: list[str] = dataclasses.field(default_factory=list)
    validation_requirements: list[str] = dataclasses.field(default_factory=list)
    provider: str = ""


@dataclasses.dataclass
class ValidationResult:
    valid: bool
    errors: list[str] = dataclasses.field(default_factory=list)
    warnings: list[str] = dataclasses.field(default_factory=list)
    missing_symbols: list[str] = dataclasses.field(default_factory=list)
    duplicate_definitions: list[str] = dataclasses.field(default_factory=list)
    compilation_result: str = "not-run"
    lint_result: str = "not-run"
    suggested_repair_type: RepairType = RepairType.LOCAL_FIX


@dataclasses.dataclass
class GeneratedArtifact:
    kind: str
    content: str
    scenarios: list[dict[str, Any]] = dataclasses.field(default_factory=list)
    confidence_score: float = 0.0
    unresolved_gaps: list[str] = dataclasses.field(default_factory=list)


@dataclasses.dataclass
class RunObservability:
    """Privacy-safe per-run routing summary; callers choose whether to persist it."""

    pack: str
    domain: str
    pack_selection: str
    outcome: str
    escalation_stage: str
    validation_state: str
    repair_state: str
    unresolved_gap_count: int
    unresolved_gap_categories: list[str] = dataclasses.field(default_factory=list)
    provider: str = ""
    model_tier: str = ""
    events: list[str] = dataclasses.field(default_factory=list)
    llm_calls: int = 0


@dataclasses.dataclass
class LlmGenerationRequest:
    task: AutomationTask
    context: LocalContextBundle
    plan: GenerationPlan
    artifact: GeneratedArtifact
    validation: ValidationResult


@dataclasses.dataclass
class LlmGenerationResponse:
    content: str
    provider: str


class LlmProvider(Protocol):
    name: str

    def generate(self, request: LlmGenerationRequest) -> LlmGenerationResponse: ...

def to_jsonable(value: Any) -> Any:
    if isinstance(value, enum.Enum):
        return value.value
    if dataclasses.is_dataclass(value):
        return {
            field.name: to_jsonable(getattr(value, field.name))
            for field in dataclasses.fields(value)
        }
    if isinstance(value, list):
        return [to_jsonable(item) for item in value]
    if isinstance(value, dict):
        return {key: to_jsonable(item) for key, item in value.items()}
    return value
