package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.ConversationHistory;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import nz.co.eroad.qaisystem.service.ConversationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zero-mock tests for {@link CodegenService}: verifies the additive one-shot overload
 * consumes {@code targetDir} (gap 1) and surfaces {@code testPath} (gap 2), while proving
 * the 1-arg Kafka entry point ({@code generateAndExecuteOne(req)}) is unchanged
 * (opens the per-scenario PR, leaves {@code testPath} null).
 */
class CodegenServiceTest {

    /** Fake generator returning canned content (no Conductor agent invoked). */
    static final class FakeConductor extends ConductorCodeGenerator {
        FakeConductor() { super(null); }
        @Override
        public String generate(BddScenario.Scenario scenario, BddScenario parent, String testType) {
            return "class FooApiTest {}";
        }
    }

    /** Fake loop that records {@code openTestPr} and returns a fresh canned result. */
    static final class CapturingLoop extends StabilizationLoop {
        Boolean seenOpenTestPr;
        CapturingLoop() { super(null, null); }
        @Override
        public TestResult execute(TestScript script, boolean openTestPr) {
            seenOpenTestPr = openTestPr;
            return TestResult.builder()
                    .scriptId(script.getScriptId())
                    .prId(script.getPrId())
                    .passed(true)
                    .finalScriptContent(script.getScriptContent())
                    .build();
        }
    }

    /** No-op conversation store. */
    static final class FakeConversationStore implements ConversationStore {
        @Override public void save(String conversationId, ConversationHistory history) { }
        @Override public Optional<ConversationHistory> load(String conversationId) { return Optional.empty(); }
        @Override public void remove(String conversationId) { }
    }

    /** Progress tracker that always claims and reports zero remaining. */
    static final class FakeProgressTracker implements CodegenProgressTracker {
        @Override public boolean claimScenario(String scenarioId) { return true; }
        @Override public int completeAndRemaining(String prId, int total) { return 0; }
    }

    private static TestScriptRequest request() {
        BddScenario.Scenario scenario = BddScenario.Scenario.builder()
                .scenarioId("sc-1")
                .title("Foo")
                .testType("API")
                .build();
        return TestScriptRequest.builder()
                .prId("PR-1")
                .prTitle("Title One")
                .bddScenarioId("bdd-1")
                .scenarioId("sc-1")
                .scenarioIndex(0)
                .scenarioCount(1)
                .scenario(scenario)
                .build();
    }

    private static CodegenService serviceWith(CapturingLoop loop) {
        return new CodegenService(new FakeConductor(), loop,
                new FakeConversationStore(), new FakeProgressTracker());
    }

    @Test
    @DisplayName("one-shot with --target-dir surfaces targetDir/fileName as testPath; openTestPr=false")
    void oneShot_withTargetDir_setsCustomTestPath() {
        CapturingLoop loop = new CapturingLoop();
        CodegenService service = serviceWith(loop);

        TestResult result = service.generateAndExecuteOne(request(), false, "custom/dir");

        assertThat(loop.seenOpenTestPr).isFalse();
        assertThat(result).isNotNull();
        assertThat(result.getTestPath()).isEqualTo("custom/dir/FooApiTest.java");
    }

    @Test
    @DisplayName("one-shot with no target-dir uses the default src/test/java/{package} path")
    void oneShot_noTargetDir_setsDefaultTestPath() {
        CapturingLoop loop = new CapturingLoop();
        CodegenService service = serviceWith(loop);

        TestResult result = service.generateAndExecuteOne(request(), false, null);

        assertThat(loop.seenOpenTestPr).isFalse();
        assertThat(result).isNotNull();
        assertThat(result.getTestPath())
                .isEqualTo("src/test/java/nz/co/eroad/qaisystem/generated/tests/api/FooApiTest.java");
    }

    @Test
    @DisplayName("1-arg Kafka entry point opens the per-scenario PR and leaves testPath null")
    void oneArg_kafkaPath_unchanged() {
        CapturingLoop loop = new CapturingLoop();
        CodegenService service = serviceWith(loop);

        TestResult result = service.generateAndExecuteOne(request());

        assertThat(loop.seenOpenTestPr).isTrue();
        assertThat(result).isNotNull();
        assertThat(result.getTestPath()).isNull();
    }
}
