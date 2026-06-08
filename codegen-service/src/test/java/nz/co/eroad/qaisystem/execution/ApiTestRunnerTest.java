package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiTestRunner code generation tests")
class ApiTestRunnerTest {
    ApiTestRunner runner;
    BddScenario.Scenario sc;
    BddScenario parent;

    @BeforeEach void setUp() {
        runner = new ApiTestRunner();
        sc = BddScenario.Scenario.builder().title("User logs in").testType("API")
            .givenSteps(List.of("valid creds")).whenSteps(List.of("POST /login"))
            .thenSteps(List.of("status is 200")).tags(List.of("smoke")).build();
        parent = BddScenario.builder().prId("PR-001").scenarioId("SC-1").build();
    }

    @Test @DisplayName("generated code has package declaration")
    void hasPackage() { assertThat(runner.generateCode(sc, parent, no())).contains("package nz.co.eroad.qaisystem.generated.tests.api;"); }

    @Test @DisplayName("class name derived from scenario title")
    void className() { assertThat(runner.generateCode(sc, parent, no())).contains("class User_logs_inApiTest"); }

    @Test @DisplayName("uses repo package from context")
    void usesRepoPackage() {
        RepoContext ctx = RepoContext.builder().contextAvailable(true).basePackage("com.ex.api").build();
        assertThat(runner.generateCode(sc, parent, ctx)).contains("package com.ex.api;");
    }

    @Test @DisplayName("extends base class from context")
    void extendsBase() {
        RepoContext ctx = RepoContext.builder().contextAvailable(true).baseTestClass("Base").build();
        assertThat(runner.generateCode(sc, parent, ctx)).contains("extends Base");
    }

    @Test @DisplayName("agent instructions in header")
    void agentHeader() {
        RepoContext ctx = RepoContext.builder().contextAvailable(true)
            .agentInstructions(Map.of("api.md","Use RA 5x")).build();
        String code = runner.generateCode(sc, parent, ctx);
        assertThat(code).contains("AGENT INSTRUCTIONS").contains("Use RA 5x");
    }

    @Test @DisplayName("200 then-step produces status assertion")
    void statusAssertion() { assertThat(runner.generateCode(sc, parent, no())).contains("isEqualTo(200)"); }

    @Test @DisplayName("PR id in javadoc")
    void prIdInDoc() { assertThat(runner.generateCode(sc, parent, no())).contains("PR-001"); }

    private RepoContext no() { return RepoContext.builder().contextAvailable(false).build(); }
}
