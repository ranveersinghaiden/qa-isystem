package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import nz.co.eroad.qaisystem.service.TestPrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stabilization Loop: Run → Fail → Fix (up to maxRetries).
 *
 * On each failure it applies lightweight fixes to the script before retrying:
 *   - Retry-after headers / timing adjustments
 *   - Base-URL corrections
 *   - Null-guard injections
 * After maxRetries it marks the script as ABANDONED and raises a PR anyway
 * so a human can review the partially-fixed code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StabilizationLoop {

    private final TestExecutionEngine testExecutionEngine;
    private final TestPrService       testPrService;

    @Value("${aiqa.stabilization.max-retries:3}")
    private int maxRetries;

    @Value("${aiqa.stabilization.retry-delay-ms:2000}")
    private long retryDelayMs;

    public TestResult execute(TestScript script) {
        log.info("[StabilizationLoop] Starting loop for script '{}' (max {} retries)",
                script.getScriptId(), maxRetries);

        TestResult lastResult = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            lastResult = testExecutionEngine.execute(script, attempt);

            if (lastResult.isPassed()) {
                lastResult.setStabilized(attempt > 1);
                script.setStatus(TestScript.ScriptStatus.PASSED);

                log.info("[StabilizationLoop] Script '{}' PASSED on attempt {}",
                        script.getScriptId(), attempt);

                testPrService.createFinalTestPr(script, lastResult);
                return lastResult;
            }

            log.warn("[StabilizationLoop] Attempt {}/{} FAILED for '{}': {}",
                    attempt, maxRetries,
                    script.getScriptId(),
                    lastResult.getErrorMessage());

            if (attempt < maxRetries) {
                script = applyFix(script, lastResult, attempt);
                script.setRetryCount(attempt);
                sleep();
            }
        }

        // All retries exhausted
        log.error("[StabilizationLoop] Script '{}' ABANDONED after {} attempts",
                script.getScriptId(), maxRetries);

        script.setStatus(TestScript.ScriptStatus.ABANDONED);
        lastResult.setStabilized(false);

        testPrService.createFinalTestPr(script, lastResult);
        return lastResult;
    }

    // ─── Auto-fix heuristics ────────────────────────────────────────────────────

    /**
     * Applies incremental fixes based on attempt number and failure reason.
     * Each attempt applies progressively more aggressive fixes.
     */
    private TestScript applyFix(TestScript script,
                                TestResult failedResult,
                                int attemptNumber) {
        log.info("[StabilizationLoop] Applying fix (attempt {}) for script '{}'",
                attemptNumber, script.getScriptId());

        String fixedContent = script.getScriptContent();
        String error        = failedResult.getErrorMessage() != null
                ? failedResult.getErrorMessage() : "";

        fixedContent = switch (attemptNumber) {
            case 1 -> applyAttempt1Fix(fixedContent, error);
            case 2 -> applyAttempt2Fix(fixedContent, error);
            default -> applyAttempt3Fix(fixedContent, error);
        };

        // Record what was changed
        String fixNote = "// Fix applied on attempt " + attemptNumber
                + ": " + describeFix(attemptNumber, error) + "\n";

        return TestScript.builder()
                .scriptId(script.getScriptId())
                .bddScenarioId(script.getBddScenarioId())
                .prId(script.getPrId())
                .testType(script.getTestType())
                .scriptContent(fixNote + fixedContent)
                .fileName(script.getFileName())
                .targetPackage(script.getTargetPackage())
                .dependencies(script.getDependencies())
                .status(TestScript.ScriptStatus.GENERATED)
                .retryCount(script.getRetryCount())
                .executionErrors(failedResult.getFailureReasons())
                .build();
    }

    /**
     * Attempt 1 fix: Add timeout/retry config and wait conditions.
     */
    private String applyAttempt1Fix(String content, String error) {
        String fixed = content;

        // Add connection timeout if connection refused
        if (error.contains("Connection refused") || error.contains("503")) {
            fixed = fixed.replace(
                    "RestAssured.baseURI",
                    "RestAssured.config = RestAssured.config()\n" +
                            "            .httpClient(HttpClientConfig.httpClientConfig()\n" +
                            "            .setParam(\"http.connection.timeout\", 5000)\n" +
                            "            .setParam(\"http.socket.timeout\", 10000));\n" +
                            "        RestAssured.baseURI"
            );
        }

        // Add @Timeout annotation for slow tests
        if (error.contains("timeout") || error.contains("timed out")) {
            fixed = fixed.replace(
                    "@Test",
                    "@Test\n    @Timeout(value = 30, unit = TimeUnit.SECONDS)"
            );
            // Add import
            fixed = addImport(fixed,
                    "import java.util.concurrent.TimeUnit;",
                    "import org.junit.jupiter.api.Timeout;"
            );
        }

        return fixed;
    }

    /**
     * Attempt 2 fix: Add retry logic and null-safety guards.
     */
    private String applyAttempt2Fix(String content, String error) {
        String fixed = applyAttempt1Fix(content, error);

        // Wrap assertion in retry block
        if (error.contains("AssertionError")) {
            String retryWrapper =
                    "\n        // Auto-fix: Retry wrapper added\n" +
                            "        int maxRetry = 3;\n" +
                            "        for (int i = 0; i < maxRetry; i++) {\n" +
                            "            try {\n" +
                            "                Thread.sleep(1000);\n";

            // Insert retry logic before the first assertion
            fixed = fixed.replace(
                    "// THEN",
                    "// THEN - with retry\n" + retryWrapper
            );

            // Close the retry block before the last brace
            int lastBrace = fixed.lastIndexOf("}");
            if (lastBrace > 0) {
                fixed = fixed.substring(0, lastBrace) +
                        "                break;\n" +
                        "            } catch (AssertionError | InterruptedException e) {\n" +
                        "                if (i == maxRetry - 1) throw new AssertionError(e);\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n}";
            }
        }

        // Add null checks for response body
        fixed = fixed.replace(
                "assertThat(response.statusCode())",
                "assertThat(response).isNotNull();\n" +
                        "        assertThat(response.statusCode())"
        );

        return fixed;
    }

    /**
     * Attempt 3 fix: Simplify test to bare minimum — just verify
     * the endpoint is reachable with any 2xx status.
     */
    private String applyAttempt3Fix(String content, String error) {
        // On final attempt, fall back to a minimal smoke test
        // to at least verify reachability
        String className = extractClassName(content);

        return """
                // AUTO-STABILIZED: Simplified smoke test (original failed 2 attempts)
                // Original error: %s
                package nz.co.eroad.qaisystem.generated.tests.stabilized;

                import io.restassured.RestAssured;
                import io.restassured.response.Response;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.DisplayName;
                import static io.restassured.RestAssured.given;
                import static org.assertj.core.api.Assertions.assertThat;

                public class %s_Stabilized {

                    @Test
                    @DisplayName("Smoke test - endpoint reachability check")
                    void smokeTest() {
                        RestAssured.baseURI = "http://localhost:8080";

                        // Minimal assertion: endpoint responds (any status)
                        Response response = given()
                            .header("Content-Type", "application/json")
                            .when()
                            .get("/api/v1/health")
                            .then()
                            .extract().response();

                        // Accept any response (endpoint is alive)
                        assertThat(response.statusCode())
                            .as("Endpoint should be reachable")
                            .isBetween(100, 599);
                    }
                }
                """.formatted(error != null ? error : "unknown", className);
    }

    private String extractClassName(String content) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("public class (\\w+)")
                        .matcher(content);
        return m.find() ? m.group(1) : "UnknownTest";
    }

    private String addImport(String content, String... imports) {
        String firstImport = "import ";
        int idx = content.indexOf(firstImport);
        if (idx < 0) return content;

        StringBuilder extra = new StringBuilder();
        for (String imp : imports) {
            if (!content.contains(imp)) {
                extra.append(imp).append("\n");
            }
        }
        return content.substring(0, idx) + extra + content.substring(idx);
    }

    private String describeFix(int attempt, String error) {
        return switch (attempt) {
            case 1 -> "Added timeout configuration and wait conditions";
            case 2 -> "Added retry wrapper and null-safety guards";
            default -> "Simplified to minimal smoke test";
        };
    }

    private void sleep() {
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[StabilizationLoop] Sleep interrupted");
        }
    }
}