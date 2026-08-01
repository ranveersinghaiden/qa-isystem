from __future__ import annotations

from pathlib import Path

from qa_engine_pack_bdd import BddPack

from local_generation import (
    AutomationTask,
    GeneratedArtifact,
    GenerationRouter,
    LocalContextBuilder,
    LocalContextBundle,
    LocalValidators,
    PackRegistry,
    TaskType,
    TestDomain as Domain,
    _task_from_json,
    default_pack_registry,
)


def _task(
    task_type: TaskType = TaskType.TEST_SKELETON_GENERATION,
    **changes: object,
) -> AutomationTask:
    values: dict[str, object] = {
        "task_id": "pack-1",
        "task_type": task_type,
        "impacted_components": ["Vehicle"],
        "acceptance_criteria": ["Vehicle behavior is covered."],
    }
    values.update(changes)
    return AutomationTask(**values)  # type: ignore[arg-type]


def _context(task: AutomationTask) -> LocalContextBundle:
    return LocalContextBuilder(Path.cwd()).build(task)


def test_registry_prefers_explicit_domain_then_task_type_and_inference() -> None:
    registry = default_pack_registry()

    assert registry.select(
        _task(test_domain=Domain.API, target_test_type="playwright")
    ).pack.domain == Domain.API
    assert registry.select(
        _task(TaskType.MOBILE_TEST_GENERATION)
    ).pack.domain == Domain.MOBILE
    assert registry.select(
        _task(target_test_type="playwright-typescript")
    ).pack.domain == Domain.WEB
    assert registry.select(
        _task(impacted_components=["mobile-qa_tooling/sample_mobile"])
    ).pack.domain == Domain.MOBILE


def test_registry_unknown_and_ambiguous_values_default_to_bdd_without_crashing() -> None:
    registry = default_pack_registry()

    unknown = registry.select(_task(test_domain="future-pack"))
    ambiguous = registry.select(_task(target_test_type="playwright appium"))

    assert unknown.pack.domain == Domain.BDD
    assert unknown.source == "default"
    assert ambiguous.pack.domain == Domain.BDD
    assert ambiguous.source == "ambiguous-fallback"
    assert "human review" in ambiguous.note


def test_partial_registry_falls_back_to_bdd_and_requires_human_review(
    tmp_path: Path,
) -> None:
    registry = PackRegistry([BddPack()])
    cases = (
        ("explicit", Domain.API, _task(test_domain=Domain.API)),
        ("explicit", Domain.WEB, _task(test_domain=Domain.WEB)),
        ("explicit", Domain.MOBILE, _task(test_domain=Domain.MOBILE)),
        ("inferred", Domain.API, _task(target_test_type="rest-client")),
        ("inferred", Domain.WEB, _task(target_test_type="playwright")),
        ("inferred", Domain.MOBILE, _task(target_test_type="appium")),
    )

    for source, domain, task in cases:
        selection = registry.select(task)

        assert selection.pack.domain == Domain.BDD
        assert selection.source == "unregistered-fallback"
        assert selection.note == (
            f"{source} selection resolved {domain.value}, but its pack is "
            "unregistered; defaulted BDD requires human review"
        )

    result = GenerationRouter(
        LocalContextBuilder(tmp_path), registry=registry
    ).route(_task(test_domain=Domain.API))

    assert result["plan"]["generation_mode"] == "HUMAN_REVIEW"
    assert result["context"]["pack_inference"] == "unregistered-fallback"
    assert (
        "explicit selection resolved api, but its pack is unregistered; "
        "defaulted BDD requires human review"
        in result["artifact"]["unresolved_gaps"]
    )


def test_legacy_task_json_and_construction_remain_compatible() -> None:
    legacy = _task(TaskType.PAGE_OBJECT_GENERATION)
    parsed = _task_from_json(
        {
            "task_id": "legacy-json",
            "task_type": "STEP_DEFINITION_GENERATION",
            "impacted_components": ["Vehicle"],
        }
    )

    assert legacy.test_domain is None
    assert parsed.task_type == TaskType.STEP_DEFINITION_GENERATION
    assert parsed.target_test_type == "java-bdd"


def test_router_uses_selected_pack_and_safe_unknown_fallback(tmp_path: Path) -> None:
    web = _task(TaskType.WEB_TEST_GENERATION)
    unknown = _task(
        TaskType.BDD_GENERATION,
        target_test_type="future-framework",
        test_domain="future-pack",
    )

    web_result = GenerationRouter(LocalContextBuilder(tmp_path)).route(web)
    unknown_result = GenerationRouter(LocalContextBuilder(tmp_path)).route(unknown)

    assert web_result["context"]["selected_test_domain"] == Domain.WEB.value
    assert web_result["artifact"]["kind"] == "web-page-object"
    assert unknown_result["context"]["selected_test_domain"] == Domain.BDD.value
    assert unknown_result["artifact"]["kind"] == "gherkin"


def test_router_escalates_unsupported_explicit_domain_with_exact_note(
    tmp_path: Path,
) -> None:
    task = _task(test_domain="future-pack")
    selection = default_pack_registry().select(task)

    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(task)

    assert result["plan"]["generation_mode"] == "HUMAN_REVIEW"
    assert result["plan"]["reason"] == selection.note
    assert selection.note in result["plan"]["required_missing_items"]


def test_router_escalates_ambiguous_domain_evidence_with_exact_note(
    tmp_path: Path,
) -> None:
    task = _task(
        target_test_type="playwright-java",
        impacted_components=["web_suite/web_suite-api-support"],
    )
    selection = default_pack_registry().select(task)

    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(task)

    assert selection.source == "ambiguous-fallback"
    assert result["plan"]["generation_mode"] == "HUMAN_REVIEW"
    assert result["plan"]["reason"] == selection.note
    assert selection.note in result["plan"]["required_missing_items"]


def test_legacy_bdd_task_remains_deterministic(tmp_path: Path) -> None:
    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(
        _task(TaskType.BDD_GENERATION)
    )

    assert result["plan"]["generation_mode"] == "DETERMINISTIC"


def test_router_escalates_api_without_endpoint_evidence(tmp_path: Path) -> None:
    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(
        _task(TaskType.API_TEST_GENERATION)
    )

    assert result["artifact"]["unresolved_gaps"] == [
        "no local endpoint evidence; endpoint remains unresolved"
    ]
    assert result["plan"]["generation_mode"] == "HUMAN_REVIEW"
    assert result["observability"]["outcome"] == "human_review_required"
    assert result["observability"]["escalation_stage"] == "human_review"
    assert result["observability"]["unresolved_gap_count"] == 1
    assert result["observability"]["unresolved_gap_categories"] == ["artifact_gap"]
    assert result["observability"]["llm_calls"] == 0


def test_router_escalates_web_without_locator_evidence(tmp_path: Path) -> None:
    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(
        _task(TaskType.WEB_TEST_GENERATION)
    )

    assert result["artifact"]["unresolved_gaps"] == [
        "no local selector evidence; locator remains unresolved"
    ]
    assert result["plan"]["generation_mode"] == "HUMAN_REVIEW"
    assert result["observability"]["llm_calls"] == 0


def test_router_escalates_mobile_without_locator_evidence(tmp_path: Path) -> None:
    result = GenerationRouter(LocalContextBuilder(tmp_path)).route(
        _task(TaskType.MOBILE_TEST_GENERATION)
    )

    assert result["artifact"]["unresolved_gaps"] == [
        "no local mobile locator evidence; locator remains unresolved"
    ]
    assert result["plan"]["generation_mode"] == "HUMAN_REVIEW"
    assert result["observability"]["llm_calls"] == 0


def test_deterministic_pack_artifacts_pass_own_policy() -> None:
    registry = default_pack_registry()
    validators = LocalValidators()

    for domain in (Domain.WEB, Domain.API, Domain.MOBILE):
        task = _task(test_domain=domain)
        context = _context(task)
        pack = registry.select(task).pack
        artifact = pack.generate(task, context)

        assert validators.validate(artifact, context, pack).valid
        assert "TODO" in artifact.content


def test_packs_normalize_java_class_names_from_component_input() -> None:
    expected_names = {
        "web_suite/web_suite-api-support": "WebSuiteWebSuiteApiSupport",
        "360 vehicle": "Generated360Vehicle",
        "!!!": "Generated",
    }
    pack_details = (
        (Domain.API, "api-client", "ApiSupport"),
        (Domain.WEB, "web-page-object", "Page"),
        (Domain.MOBILE, "mobile-screen", "Screen"),
    )
    registry = default_pack_registry()

    for component, expected_name in expected_names.items():
        for domain, kind, suffix in pack_details:
            task = _task(test_domain=domain, impacted_components=[component])
            artifact = registry.select(task).pack.generate(task, _context(task))

            assert artifact.kind == kind
            assert f"public class {expected_name}{suffix}" in artifact.content


def test_api_pack_generates_required_java_support_and_rejects_missing_support() -> None:
    task = _task(test_domain=Domain.API)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    artifact = pack.generate(task, context)

    assert artifact.content.startswith(f"package {context.target_package};")
    assert "import java.util.Locale;" in artifact.content
    assert "import java.util.Map;" in artifact.content
    assert "import static java.util.stream.Collectors.toMap;" in artifact.content
    assert "import lombok.extern.slf4j.Slf4j;" in artifact.content
    assert "import com.example.utils.EnvGuard;" in artifact.content
    assert "@Slf4j" in artifact.content
    assert pack.validate(
        GeneratedArtifact(
            "api-client",
            artifact.content.replace("import java.util.Map;\n", "").replace(
                "@Slf4j\n", ""
            ),
        ),
        context,
    ).errors == [
        "API Map references require java.util.Map import",
        "API logging requires @Slf4j",
    ]
    assert (
        "API EnvGuard references require com.example.utils.EnvGuard import"
        in pack.validate(
            GeneratedArtifact(
                "api-client",
                artifact.content.replace(
                    "import com.example.utils.EnvGuard;\n", ""
                ),
            ),
            context,
        ).errors
    )


def test_web_pack_rejects_direct_page_access_and_unprefixed_locators() -> None:
    task = _task(test_domain=Domain.WEB)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    artifact = GeneratedArtifact(
        "web-page-object",
        """@Slf4j
public class VehiclePage {
    private Locator submitButton;
    void submit() { page.click("button"); }
}
""",
    )

    result = pack.validate(artifact, context)

    assert not result.valid
    assert any("direct page" in error for error in result.errors)
    assert any("locator fields" in error for error in result.errors)


def test_web_pack_rejects_whitespace_variant_thread_sleep() -> None:
    task = _task(test_domain=Domain.WEB)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    artifact = GeneratedArtifact(
        "web-page-object",
        "@Slf4j\npublic class VehiclePage { void waitForIt() throws Exception { Thread . sleep (1); } }",
    )

    assert "Thread.sleep is prohibited" in pack.validate(artifact, context).errors
    assert "Thread.sleep is prohibited" in LocalValidators().validate(
        artifact, context, pack
    ).errors


def test_api_pack_rejects_unsafe_contract_patterns() -> None:
    task = _task(test_domain=Domain.API)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    artifact = GeneratedArtifact(
        "api-client",
        """public class VehicleApiSupport {
    public String getVehicle(String id) {
        EnvGuard.ensureEnvSafety();
        String mode = "fixed";
        return response.length() > 0 ? response : "";
    }
    public static void deleteVehicle(String id) { }
    public <T> T patchVehicle(String payload) { return null; }
}
""",
    )

    result = pack.validate(artifact, context)

    assert not result.valid
    assert any("read method" in error for error in result.errors)
    assert any("non-empty" in error for error in result.errors)
    assert any("delete method" in error for error in result.errors)
    assert any("deleteVehicle requires input guards" in error for error in result.errors)
    assert any("deleteVehicle requires EnvGuard" in error for error in result.errors)
    assert any("patchVehicle requires input guards" in error for error in result.errors)
    assert any("patchVehicle requires EnvGuard" in error for error in result.errors)


def test_api_pack_rejects_write_without_success_check() -> None:
    task = _task(test_domain=Domain.API)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    artifact = GeneratedArtifact(
        "api-client",
        """
        public class VehicleApiSupport {
            public String createVehicle(String payload) {
                requireValue(payload, "payload");
                EnvGuard.ensureEnvSafety();
                String response = execute(payload);
                return response;
            }

            private boolean isExplicitSuccess(String response) {
                return "OK".equals(response);
            }
        }
        """,
    )

    result = pack.validate(artifact, context)

    assert not result.valid
    assert "API write method createVehicle requires explicit success check" in result.errors


def test_api_pack_delete_requires_explicit_success_check() -> None:
    task = _task(test_domain=Domain.API)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    generated = pack.generate(task, context)

    assert pack.validate(generated, context).valid
    assert (
        'if (!isExplicitSuccess(response)) {\n'
        '     throw new IllegalStateException("Unexpected API success contract");\n'
        " }\n"
        ' log.info("Delete response: {}", response);'
    ) in generated.content

    unchecked = GeneratedArtifact(
        "api-client",
        """import static java.util.stream.Collectors.toMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import com.example.utils.EnvGuard;
@Slf4j
public class VehicleApiSupport {
    public void deleteResource(String id) {
        requireValue(id, "id");
        EnvGuard.ensureEnvSafety();
        String response = execute(id);
        log.info("Delete response: {}", response);
    }
    private boolean isExplicitSuccess(String response) {
        return "OK".equals(response);
    }
    private Map<String, Object> lowercaseKeys(Map<String, Object> response) {
        return response.entrySet().stream().collect(toMap(
            entry -> entry.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue));
    }
}""",
    )
    result = pack.validate(unchecked, context)

    assert not result.valid
    assert "API write method deleteResource requires explicit success check" in result.errors


def test_api_pack_delete_requires_response_value_in_logger() -> None:
    task = _task(test_domain=Domain.API)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    generated = pack.generate(task, context)
    fake_response_log = GeneratedArtifact(
        "api-client",
        generated.content.replace(
            'log.info("Delete response: {}", response);',
            'log.info("Delete response: {}", "response");',
        ),
    )

    assert pack.validate(generated, context).valid
    result = pack.validate(fake_response_log, context)
    assert not result.valid
    assert "API delete method deleteResource must log full response" in result.errors


def test_api_pack_rejects_message_only_error_logging() -> None:
    task = _task(test_domain=Domain.API)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    artifact = GeneratedArtifact(
        "api-client",
        """public class VehicleApiSupport {
    public String readVehicle(String id) {
        requireValue(id, "id");
        try {
            return "TODO";
        } catch (Exception e) {
            log.error("Vehicle read failed", e.getMessage());
            return "";
        }
    }
}
""",
    )

    result = pack.validate(artifact, context)

    assert "API log.error must pass Throwable as final argument" in result.errors


def test_bdd_pack_requires_leading_tags_only_for_gherkin() -> None:
    task = _task(TaskType.BDD_GENERATION, test_domain=Domain.BDD)
    context = _context(task)
    pack = default_pack_registry().select(task).pack

    assert not pack.validate(
        GeneratedArtifact("gherkin", "Feature: Vehicle\n"), context
    ).valid
    assert pack.validate(
        GeneratedArtifact("api-client", "Feature: Vehicle\n"), context
    ).valid
    assert pack.validate(pack.generate(task, context), context).valid


def test_mobile_pack_rejects_sample_mobiler_pagefactory_and_step_config() -> None:
    task = _task(test_domain=Domain.MOBILE)
    context = _context(task)
    pack = default_pack_registry().select(task).pack
    unsafe_screen = GeneratedArtifact(
        "mobile-screen",
        """@AndroidFindBy(id = "vehicle")
private static AppiumSample Mobiler sample_mobiler;
void open() { sample_mobiler.click(); Thread . sleep (1); }
""",
    )
    unsafe_steps = GeneratedArtifact(
        "mobile-step-definition",
        "String username = AppConfig.getProperty(\"username\");",
    )

    screen_result = LocalValidators().validate(unsafe_screen, context, pack)
    steps_result = pack.validate(unsafe_steps, context)

    assert not screen_result.valid
    assert "Thread.sleep is prohibited" in screen_result.errors
    assert any("PageFactory" in error for error in screen_result.errors)
    assert any("static AppiumSample Mobiler" in error for error in screen_result.errors)
    assert any("ActionEngine" in error for error in screen_result.errors)
    assert not steps_result.valid
    assert any("AppConfig.getProperty" in error for error in steps_result.errors)
