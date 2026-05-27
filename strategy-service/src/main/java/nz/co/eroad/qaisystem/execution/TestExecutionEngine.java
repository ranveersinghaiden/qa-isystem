package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Simulates test execution.
 *
 * In production this would:
 *  - Write the script file to disk
 *  - Compile it (javax.tools.JavaCompiler)
 *  - Run via JUnit Platform Launcher or Maven Surefire subprocess
 *  - Parse Surefire XML / stdout for results
 */
@Slf4j
@Service
public class TestExecutionEngine {

    public TestResult execute(TestScript script, int attemptNumber) {
        log.info("[TestExecutionEngine] Executing script '{}' (attempt {})",
                script.getScriptId(), attemptNumber);

        long start = System.currentTimeMillis();
        SimResult sim = simulate(script, attemptNumber);
        long elapsed = System.currentTimeMillis() - start;

        TestResult result = TestResult.builder()
                .resultId(UUID.randomUUID().toString())
                .scriptId(script.getScriptId())
                .prId(script.getPrId())
                .passed(sim.passed())
                .attemptNumber(attemptNumber)
                .executionTimeMs(elapsed)
                .output(sim.output())
                .errorMessage(sim.passed() ? null : sim.error())
                .failureReasons(sim.passed() ? List.of() : sim.reasons())
                .executedAt(LocalDateTime.now())
                .stabilized(false)
                .finalScriptContent(script.getScriptContent())
                .build();

        log.info("[TestExecutionEngine] Script '{}' attempt {} → {}",
                script.getScriptId(), attemptNumber,
                result.isPassed() ? "PASS" : "FAIL");

        return result;
    }

    // ─── Simulation ────────────────────────────────────────────────────────────

    private SimResult simulate(TestScript script, int attempt) {
        /*
         * Simple heuristic simulation:
         *   attempt 1 → 60 % pass
         *   attempt 2 → 80 % pass
         *   attempt 3 → 95 % pass
         * Real implementation compiles & runs the code.
         */
        double passChance = switch (attempt) {
            case 1  -> 0.60;
            case 2  -> 0.80;
            default -> 0.95;
        };

        boolean passed = Math.random() < passChance;

        if (passed) {
            return new SimResult(true,
                    "All assertions passed for " + script.getScriptId(),
                    null, List.of());
        } else {
            List<String> reasons = new ArrayList<>();
            reasons.add("Connection refused to test endpoint");
            if (attempt == 1) reasons.add("Test environment not fully initialised");
            return new SimResult(false,
                    "Test execution failed",
                    "AssertionError: expected 200 but was 503",
                    reasons);
        }
    }

    private record SimResult(boolean passed, String output,
                             String error, List<String> reasons) {}
}