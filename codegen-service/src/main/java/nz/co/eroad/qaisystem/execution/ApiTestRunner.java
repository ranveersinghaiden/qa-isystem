package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${aiqa.base-url:http://localhost:8080}")
    private String apiBaseUrl;

    /** Generates a RestAssured/JUnit 5 test class source string for the given BDD scenario. */
    public String generateCode(BddScenario.Scenario scenario,
                               BddScenario parent,
                               RepoContext context) {
        log.debug("[ApiTestRunner] Generating API test for '{}' (context={})",
                scenario.getTitle(), context.isContextAvailable());

        String pkg        = context.effectivePackage("nz.co.eroad.qaisystem.generated.tests.api");
        String className  = toClassName(scenario.getTitle());
        String baseUrl    = this.apiBaseUrl;
        String httpMethod = extractHttpMethod(scenario.getWhenSteps());
        String endpoint   = extractEndpointPath(scenario.getWhenSteps());
        String prCtxBlock = buildPrContextComment(parent);

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
                %s */
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
                            .%s("%s")
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
                prCtxBlock,
                className,
                context.extendsClause(),
                baseUrl,
                scenario.getTitle(),
                className.toLowerCase(),
                stepsToComments(scenario.getGivenSteps()),
                httpMethod,
                endpoint,
                stepsToAssertions(scenario.getThenSteps())
        );
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** Infers the HTTP method from the When-step text; defaults to {@code get}. */
    private String extractHttpMethod(java.util.List<String> whenSteps) {
        if (whenSteps == null || whenSteps.isEmpty()) return "get";
        String combined = String.join(" ", whenSteps).toLowerCase();
        if (combined.contains("post"))   return "post";
        if (combined.contains("put"))    return "put";
        if (combined.contains("delete")) return "delete";
        if (combined.contains("patch"))  return "patch";
        return "get";
    }

    /** Extracts the first URL path segment found in the When steps; defaults to {@code /api/v1/test}. */
    private String extractEndpointPath(java.util.List<String> whenSteps) {
        if (whenSteps == null || whenSteps.isEmpty()) return "/api/v1/test";
        var pathPattern = java.util.regex.Pattern.compile("(/[a-zA-Z0-9/{}_.\\-]+)");
        for (String step : whenSteps) {
            var m = pathPattern.matcher(step);
            if (m.find()) return m.group(1);
        }
        return "/api/v1/test";
    }

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

    private String buildPrContextComment(BddScenario parent) {
        var ctx = parent.getPrContext();
        if (ctx == null || !ctx.hasContext()) return " *";
        var sb = new StringBuilder(" *\n");
        if (ctx.hasJira() && ctx.getJiraIds() != null)
            sb.append(" * Jira       : ").append(String.join(", ", ctx.getJiraIds())).append("\n");
        if (ctx.hasJira() && ctx.getJiraLinks() != null)
            ctx.getJiraLinks().forEach(l -> sb.append(" * Jira link  : ").append(l).append("\n"));
        if (ctx.hasConfluence())
            ctx.getConfluenceLinks().forEach(l -> sb.append(" * Confluence : ").append(l).append("\n"));
        if (ctx.hasProducts())
            sb.append(" * Products   : ").append(String.join(", ", ctx.getProducts())).append("\n");
        if (ctx.hasLabels())
            sb.append(" * Labels     : ").append(String.join(", ", ctx.getLabels())).append("\n");
        return sb.toString().stripTrailing();
    }
}