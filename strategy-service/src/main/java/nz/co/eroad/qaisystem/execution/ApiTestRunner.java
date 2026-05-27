package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Generates RestAssured / JUnit 5 test code from a BDD scenario.
 * When a {@link RepoContext} is available the generated class:
 *   - uses the repo's existing package structure
 *   - extends the repo's base test class (if one exists)
 *   - includes the repo's common imports
 *   - embeds references to representative existing tests as comments
 */
@Slf4j
@Component
public class ApiTestRunner {

    public String generateCode(BddScenario.Scenario scenario,
                               BddScenario parent,
                               RepoContext context) {
        log.debug("[ApiTestRunner] Generating API test for '{}' (context={})",
                scenario.getTitle(), context.isContextAvailable());

        String pkg       = context.effectivePackage("nz.co.eroad.qaisystem.generated.tests.api");
        String className = toClassName(scenario.getTitle());
        String baseUrl   = "http://localhost:8080"; // overridden in CI / per-service config

        return """
                %s
                package %s;

                import io.restassured.RestAssured;
                import io.restassured.response.Response;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.DisplayName;
                import static io.restassured.RestAssured.given;
                import static org.assertj.core.api.Assertions.assertThat;
                %s
                /**
                 * Auto-generated API test for PR : %s
                 * Scenario                       : %s
                 * Tags                           : %s
                 * Context repo                   : %s
                 */
                public class %sApiTest%s {

                    @BeforeEach
                    void setUp() {
                        RestAssured.baseURI = "%s";
                    }

                    @Test
                    @DisplayName("%s")
                    void test_%s() {
                        // GIVEN
                        %s

                        // WHEN
                        Response response = given()
                            .header("Content-Type", "application/json")
                            .when()
                            .get("/api/v1/test")   // TODO: resolve endpoint from BDD scenario
                            .then()
                            .extract().response();

                        // THEN
                        %s
                    }
                }
                """.formatted(
                context.contextHeader(),
                pkg,
                context.commonImportsBlock(),
                parent.getPrId(),
                scenario.getTitle(),
                scenario.getTags(),
                context.isContextAvailable() ? context.getRepoModulePath() : "built-in template",
                className,
                context.extendsClause(),
                baseUrl,
                scenario.getTitle(),
                className.toLowerCase(),
                stepsToComments(scenario.getGivenSteps()),
                stepsToAssertions(scenario.getThenSteps())
        );
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String toClassName(String title) {
        return title.replaceAll("[^A-Za-z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private String stepsToComments(java.util.List<String> steps) {
        if (steps == null || steps.isEmpty()) return "// no given steps";
        return steps.stream().map(s -> "// " + s).collect(Collectors.joining("\n        "));
    }

    private String stepsToAssertions(java.util.List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return "assertThat(response.statusCode()).isBetween(200, 299);";
        }
        StringBuilder sb = new StringBuilder();
        for (String step : steps) {
            if      (step.contains("200"))              sb.append("assertThat(response.statusCode()).isEqualTo(200);\n        ");
            else if (step.contains("4xx") || step.contains("5xx")) sb.append("assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);\n        ");
            else if (step.contains("2000ms"))           sb.append("assertThat(response.time()).isLessThan(2000L);\n        ");
            else                                        sb.append("// ").append(step).append("\n        ");
        }
        return sb.toString().trim();
    }
}