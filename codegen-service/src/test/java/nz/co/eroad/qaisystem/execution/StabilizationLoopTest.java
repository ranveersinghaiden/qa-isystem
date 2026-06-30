package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import nz.co.eroad.qaisystem.service.TestPrService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zero-mock tests for {@link StabilizationLoop}: verifies the additive
 * {@code execute(script, openTestPr)} overload while proving the 1-arg
 * Kafka path ({@code execute(script)} → {@code execute(script, true)}) is unchanged.
 */
class StabilizationLoopTest {

    /** Fake engine that always returns a PASS on the first attempt (no compile/run). */
    static final class PassingEngine extends TestExecutionEngine {
        @Override
        public TestResult execute(TestScript script, int attemptNumber) {
            return TestResult.builder()
                    .scriptId(script.getScriptId())
                    .prId(script.getPrId())
                    .passed(true)
                    .build();
        }
    }

    /** Fake PR service that counts how many times the per-scenario PR is opened. */
    static final class CountingTestPrService extends TestPrService {
        int finalPrCalls;
        CountingTestPrService() { super(null, null, null); }
        @Override
        public String createFinalTestPr(TestScript script, TestResult result) {
            finalPrCalls++;
            return "http://pr/final";
        }
    }

    private static StabilizationLoop loopWith(TestPrService prService) {
        StabilizationLoop loop = new StabilizationLoop(new PassingEngine(), prService);
        // @Value fields are unset under plain construction — give the loop a real retry budget.
        setMaxRetries(loop, 3);
        return loop;
    }

    private static void setMaxRetries(StabilizationLoop loop, int value) {
        try {
            Field f = StabilizationLoop.class.getDeclaredField("maxRetries");
            f.setAccessible(true);
            f.setInt(loop, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set maxRetries", e);
        }
    }

    private static TestScript script() {
        return TestScript.builder()
                .scriptId("sc-1")
                .prId("PR-1")
                .scriptContent("class FooApiTest {}")
                .fileName("FooApiTest.java")
                .targetPackage("nz.co.eroad.qaisystem.generated.tests.api")
                .testType(TestScript.TestType.API)
                .status(TestScript.ScriptStatus.GENERATED)
                .build();
    }

    @Test
    @DisplayName("execute(script, true) opens the per-scenario PR exactly once (Kafka behavior)")
    void openTestPrTrue_opensPr() {
        CountingTestPrService prService = new CountingTestPrService();
        StabilizationLoop loop = loopWith(prService);

        TestResult result = loop.execute(script(), true);

        assertThat(result).isNotNull();
        assertThat(result.isPassed()).isTrue();
        assertThat(prService.finalPrCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("1-arg execute(script) delegates to execute(script, true) — unchanged Kafka path")
    void oneArg_delegatesToOpenTestPrTrue() {
        CountingTestPrService prService = new CountingTestPrService();
        StabilizationLoop loop = loopWith(prService);

        TestResult result = loop.execute(script());

        assertThat(result).isNotNull();
        assertThat(prService.finalPrCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("execute(script, false) skips the PR and populates finalScriptContent (one-shot)")
    void openTestPrFalse_skipsPr_setsFinalScriptContent() {
        CountingTestPrService prService = new CountingTestPrService();
        StabilizationLoop loop = loopWith(prService);
        TestScript script = script();

        TestResult result = loop.execute(script, false);

        assertThat(result).isNotNull();
        assertThat(prService.finalPrCalls).isZero();
        assertThat(result.getFinalScriptContent()).isEqualTo(script.getScriptContent());
    }
}
