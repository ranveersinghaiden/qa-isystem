package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Generates Selenium / JUnit 5 test code from a BDD scenario.
 * Uses {@link RepoContext} to match the target repo's package, base class, and imports.
 */
@Slf4j
@Component
public class UITestRunner {

    public String generateCode(BddScenario.Scenario scenario,
                               BddScenario parent,
                               RepoContext context) {
        log.debug("[UITestRunner] Generating UI test for '{}' (context={})",
                scenario.getTitle(), context.isContextAvailable());

        String pkg       = context.effectivePackage("nz.co.eroad.qaisystem.generated.tests.ui");
        String className = toClassName(scenario.getTitle());

        return """
                %s
                package %s;

                import org.junit.jupiter.api.*;
                import org.openqa.selenium.WebDriver;
                import org.openqa.selenium.chrome.ChromeDriver;
                import org.openqa.selenium.chrome.ChromeOptions;
                import static org.assertj.core.api.Assertions.assertThat;
                %s
                /**
                 * Auto-generated UI test for PR : %s
                 * Scenario                      : %s
                 * Context repo                  : %s
                 */
                public class %sUiTest%s {

                    private WebDriver driver;

                    @BeforeEach
                    void setUp() {
                        ChromeOptions options = new ChromeOptions();
                        options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage");
                        driver = new ChromeDriver(options);
                    }

                    @AfterEach
                    void tearDown() {
                        if (driver != null) driver.quit();
                    }

                    @Test
                    @DisplayName("%s")
                    void test_%s() {
                        // GIVEN
                        %s

                        // WHEN
                        driver.get("http://localhost:3000"); // TODO: resolve base URL
                        %s

                        // THEN
                        assertThat(driver.getTitle()).isNotEmpty();
                        %s
                    }
                }
                """.formatted(
                context.contextHeader(),
                pkg,
                context.commonImportsBlock(),
                parent.getPrId(),
                scenario.getTitle(),
                context.isContextAvailable() ? context.getRepoModulePath() : "built-in template",
                className,
                context.extendsClause(),
                scenario.getTitle(),
                className.toLowerCase(),
                stepsToComments(scenario.getGivenSteps()),
                stepsToComments(scenario.getWhenSteps()),
                stepsToComments(scenario.getThenSteps())
        );
    }

    private String toClassName(String title) {
        return title.replaceAll("[^A-Za-z0-9]", "_")
                .replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private String stepsToComments(java.util.List<String> steps) {
        if (steps == null || steps.isEmpty()) return "";
        return steps.stream().map(s -> "// " + s).collect(Collectors.joining("\n        "));
    }
}
