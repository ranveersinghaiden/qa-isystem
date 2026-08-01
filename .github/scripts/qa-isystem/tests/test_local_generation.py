from __future__ import annotations

import json
import sys
from pathlib import Path

from local_generation import (
    AutomationTask,
    ContextBudget,
    DeterministicBddGenerator,
    GeneratedArtifact,
    GenerationMode,
    GenerationRouter,
    LocalContextBuilder,
    LocalRepairEngine,
    LocalValidators,
    TaskType,
    main,
)
from run_local_generation import _acceptance_criteria


class _Provider:
    def __init__(self, name: str, responses: list[str]) -> None:
        self.name, self.responses, self.calls = name, responses, 0

    def generate(self, request):
        from local_generation import LlmGenerationResponse

        response = self.responses[min(self.calls, len(self.responses) - 1)]
        self.calls += 1
        return LlmGenerationResponse(response, self.name)


def _task(kind: TaskType = TaskType.BDD_GENERATION) -> AutomationTask:
    return AutomationTask(
        task_id="local-1",
        task_type=kind,
        impacted_components=["Vehicle"],
        acceptance_criteria=["A user can create a vehicle with valid input."],
        pr_diff_summary="Add create vehicle endpoint.",
    )


def test_compatibility_facade_exposes_composable_engine_types() -> None:
    from qa_engine_context import LocalContextBuilder as ContextBuilder
    from qa_engine_generators import DeterministicBddGenerator as BddGenerator
    from qa_engine_router import GenerationRouter as Router

    assert LocalContextBuilder is ContextBuilder
    assert DeterministicBddGenerator is BddGenerator
    assert GenerationRouter is Router


def test_context_builder_bounds_and_discovers_local_evidence(tmp_path: Path) -> None:
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "pageObjects").mkdir()
    (tmp_path / "src" / "pageObjects" / "VehicleAgent.java").write_text(
        "public class VehicleSteps { public void createVehicle(String id) {} }", encoding="utf-8"
    )
    (tmp_path / "src" / "vehicle.feature").write_text("@Smoke\nFeature: Vehicle", encoding="utf-8")
    context = LocalContextBuilder(tmp_path, ContextBudget(max_files=1)).build(_task())
    assert len(context.relevant_files) == 1
    assert context.existing_feature_examples == ["src/vehicle.feature"]
    assert context.available_agent_methods["src/pageObjects/VehicleAgent.java"] == ["createVehicle(String id)"]


def test_main_rejects_task_without_task_type(
    tmp_path: Path, monkeypatch, capsys
) -> None:
    task_file = tmp_path / "task.json"
    task_file.write_text(json.dumps({"task_id": "missing-type"}), encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["local_generation.py", "--task", str(task_file)])

    assert main() == 2
    assert "Invalid AutomationTask JSON" in capsys.readouterr().err


def test_main_rejects_invalid_task_list_field(
    tmp_path: Path, monkeypatch, capsys
) -> None:
    task_file = tmp_path / "task.json"
    task_file.write_text(
        json.dumps({
            "task_id": "invalid-list",
            "task_type": "BDD_GENERATION",
            "impacted_components": None,
        }),
        encoding="utf-8",
    )
    monkeypatch.setattr(sys, "argv", ["local_generation.py", "--task", str(task_file)])

    assert main() == 2
    assert "impacted_components must be a list of strings" in capsys.readouterr().err


def test_convention_validator_requires_steps_for_each_gherkin_scenario() -> None:
    context = LocalContextBuilder(Path.cwd()).build(_task())
    artifact = GeneratedArtifact(
        "gherkin",
        """Feature: Vehicle

Scenario: valid vehicle
  Given a valid vehicle request
  When the client submits the vehicle
  Then the vehicle is created

Scenario: incomplete vehicle
  Given an invalid vehicle request
  Then the validation error is returned
""",
    )

    result = LocalValidators().validate(artifact, context)

    assert not result.valid
    assert "each scenario must contain one Given, When, and Then" in result.errors


def test_convention_validator_rejects_gherkin_without_scenarios() -> None:
    artifact = GeneratedArtifact("gherkin", "Feature: Vehicle\n")

    result = LocalValidators().validate(
        artifact, LocalContextBuilder(Path.cwd()).build(_task())
    )

    assert not result.valid
    assert "each scenario must contain one Given, When, and Then" in result.errors


def test_bdd_invalid_input_template_separates_precondition_action_and_outcome() -> None:
    task = _task()
    task.acceptance_criteria = ["Invalid vehicle input returns a validation error."]

    artifact = DeterministicBddGenerator().generate(
        task, LocalContextBuilder(Path.cwd()).build(task)
    )

    assert artifact.scenarios == [{
        "name": "Invalid Vehicle input",
        "given": "invalid Vehicle input",
        "when": "the client submits Vehicle",
        "then": "the Vehicle validation error is returned",
    }]
    assert "  Given invalid Vehicle input" in artifact.content
    assert "  When the client submits Vehicle" in artifact.content
    assert "  Then the Vehicle validation error is returned" in artifact.content


def test_page_object_generator_uses_validator_recognized_slf4j_annotation() -> None:
    task = _task(TaskType.PAGE_OBJECT_GENERATION)
    context = LocalContextBuilder(Path.cwd()).build(task)
    artifact = GenerationRouter(LocalContextBuilder(Path.cwd())).page_objects.generate(
        task, context
    )

    assert "import lombok.extern.slf4j.Slf4j;" in artifact.content
    assert "@Slf4j" in artifact.content
    assert LocalValidators().validate(artifact, context).valid


def test_bdd_generator_uses_crud_template_and_validates() -> None:
    task = _task()
    context = LocalContextBuilder(Path.cwd()).build(task)
    artifact = DeterministicBddGenerator().generate(task, context)
    result = LocalValidators().validate(artifact, context)
    assert "Scenario: Vehicle can be created" in artifact.content
    assert result.valid


def test_task_builder_bounds_local_requirements() -> None:
    criteria = _acceptance_criteria("\n".join(f"- requirement {index}" for index in range(15)))
    assert criteria == [f"requirement {index}" for index in range(12)]


def test_router_keeps_high_confidence_generation_local(tmp_path: Path) -> None:
    (tmp_path / "vehicle.feature").write_text("@Regression\nFeature: Vehicle", encoding="utf-8")
    (tmp_path / "VehicleSteps.java").write_text("public class VehicleSteps {}", encoding="utf-8")
    (tmp_path / "pageObjects").mkdir()
    (tmp_path / "pageObjects" / "VehicleAgent.java").write_text("public class VehicleAgent {}", encoding="utf-8")
    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(_task())
    assert result["plan"]["generation_mode"] == GenerationMode.DETERMINISTIC.value
    assert result["observability"] == {
        "pack": "bdd",
        "domain": "bdd",
        "pack_selection": "task_type",
        "outcome": "deterministic_pass",
        "escalation_stage": "deterministic",
        "validation_state": "passed",
        "repair_state": "not_needed",
        "unresolved_gap_count": 0,
        "unresolved_gap_categories": [],
        "provider": "",
        "model_tier": "",
        "events": [
            "local_context",
            "pack:bdd:task_type",
            "deterministic_generation",
            "local_validation",
        ],
        "llm_calls": 0,
    }


def test_main_writes_observability_to_explicit_output_path(
    tmp_path: Path, monkeypatch, capsys
) -> None:
    task_file = tmp_path / "task.json"
    output_file = tmp_path / "result.json"
    task_file.write_text(
        json.dumps({
            "task_id": "observability-cli",
            "task_type": "BDD_GENERATION",
            "impacted_components": ["Vehicle"],
            "acceptance_criteria": ["A user can create a vehicle."],
        }),
        encoding="utf-8",
    )
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "local_generation.py",
            "--repo",
            str(tmp_path),
            "--task",
            str(task_file),
            "--output",
            str(output_file),
        ],
    )

    assert main() == 0

    payload = json.loads(output_file.read_text(encoding="utf-8"))
    assert capsys.readouterr().out == ""
    assert payload["observability"]["outcome"] == "deterministic_pass"
    assert payload["observability"]["unresolved_gap_categories"] == []


def test_repair_removes_duplicate_imports() -> None:
    context = LocalContextBuilder(Path.cwd()).build(_task(TaskType.TEST_SKELETON_GENERATION))
    artifact = GenerationRouter(LocalContextBuilder(Path.cwd())).skeleton.generate(_task(TaskType.TEST_SKELETON_GENERATION), context)
    artifact.content = artifact.content.replace("import io.cucumber.java.en.Given;", "import io.cucumber.java.en.Given;\nimport io.cucumber.java.en.Given;")
    repaired = LocalRepairEngine().repair(artifact, context, LocalValidators().validate(artifact, context))
    assert repaired.content.count("import io.cucumber.java.en.Given;") == 1


def test_duplicate_validator_detects_near_existing_scenario() -> None:
    task = _task()
    context = LocalContextBuilder(Path.cwd()).build(task)
    context.relevant_file_snippets = {
        "existing.feature": ["Feature: Vehicle\n  Scenario: Vehicle can be created!\n"]
    }
    artifact = DeterministicBddGenerator().generate(task, context)
    result = LocalValidators().validate(artifact, context)
    assert not result.valid
    assert any("~=" in item for item in result.duplicate_definitions)


def test_router_escalates_after_two_invalid_cheap_responses(tmp_path: Path) -> None:
    (tmp_path / "features").mkdir()
    (tmp_path / "features" / "vehicle.feature").write_text(
        "Feature: Vehicle\n", encoding="utf-8"
    )
    cheap = _Provider("cheap", ["not gherkin"])
    premium = _Provider(
        "premium",
        ["@Regression\nFeature: Vehicle\n\n    Scenario: valid\n    Given a precondition\n    When an action occurs\n    Then an outcome is returned\n"],
    )
    task = _task()
    task.acceptance_criteria = []
    result = GenerationRouter(LocalContextBuilder(tmp_path), cheap, premium).route(task)
    assert cheap.calls == 2
    assert premium.calls == 1
    assert result["plan"]["generation_mode"] == GenerationMode.PREMIUM_LLM.value
    assert result["observability"]["outcome"] == "llm_escalation_pass"
    assert result["observability"]["escalation_stage"] == "PREMIUM_LLM"
    assert result["observability"]["provider"] == "premium"
    assert result["observability"]["model_tier"] == "PREMIUM_LLM"
    assert result["observability"]["llm_calls"] == 3


def test_step_definition_engine_reuses_verified_local_action(tmp_path: Path) -> None:
    source = tmp_path / "src"
    source.mkdir()
    (source / "Vehicle.feature").write_text(
        "@Regression\nFeature: Vehicle\n",
        encoding="utf-8",
    )
    (source / "VehicleSteps.java").write_text(
        'package example;\n\npublic class VehicleSteps {\n'
        '    @Given("an existing vehicle")\n'
        "    public void anExistingVehicle() {}\n}\n",
        encoding="utf-8",
    )
    (source / "VehicleAgent.java").write_text(
        "package example;\n\npublic class VehicleAgent {\n"
        "    public void createVehicle() {}\n}\n",
        encoding="utf-8",
    )

    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(
        _task(TaskType.STEP_DEFINITION_GENERATION)
    )

    assert result["plan"]["generation_mode"] == GenerationMode.DETERMINISTIC.value
    assert "import example.VehicleAgent;" in result["artifact"]["content"]
    assert "vehicleAgent.createVehicle();" in result["artifact"]["content"]


def test_page_object_engine_preserves_framework_safety_constraints(tmp_path: Path) -> None:
    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(
        _task(TaskType.PAGE_OBJECT_GENERATION)
    )

    assert result["plan"]["generation_mode"] == "HUMAN_REVIEW"
    content = result["artifact"]["content"]
    assert "import lombok.extern.slf4j.Slf4j;" in content
    assert "@Slf4j" in content
    assert "sample_mobiler." not in content
    assert "page." not in content


def test_review_engine_reports_local_policy_violations(tmp_path: Path) -> None:
    source = tmp_path / "src"
    source.mkdir()
    (source / "VehiclePage.java").write_text(
        "public class VehiclePage {\n"
        "    public void open() throws Exception { Thread.sleep(1); }\n"
        "}\n",
        encoding="utf-8",
    )
    (source / "VehicleSteps.java").write_text(
        'public class VehicleSteps {\n'
        '    @Given("a duplicate vehicle") public void first() {}\n'
        '    @Given("a duplicate vehicle") public void second() {}\n'
        "}\n",
        encoding="utf-8",
    )

    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(
        _task(TaskType.VALIDATION_ONLY)
    )

    assert result["plan"]["generation_mode"] == GenerationMode.DETERMINISTIC.value
    assert "Thread.sleep is prohibited" in result["artifact"]["content"]
    assert "missing @Slf4j" in result["artifact"]["content"]
    assert "duplicate Cucumber step pattern" in result["artifact"]["content"]


def test_router_uses_cheap_provider_before_premium_for_novel_work(tmp_path: Path) -> None:
    cheap = _Provider(
        "cheap",
        ["Feature: Vehicle", "Feature: Vehicle"],
    )
    premium = _Provider(
        "premium",
        ["@Regression\nFeature: Vehicle\n\nScenario: valid\nGiven a\nWhen b\nThen c\n"],
    )
    task = _task()
    task.acceptance_criteria = []
    task.attempt_number = 3

    result = GenerationRouter(LocalContextBuilder(tmp_path), cheap, premium).route(task)

    assert cheap.calls == 2
    assert premium.calls == 1
    assert result["plan"]["generation_mode"] == GenerationMode.PREMIUM_LLM.value
