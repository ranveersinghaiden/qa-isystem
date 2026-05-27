package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Generates Appium / JUnit 5 test code from a BDD scenario.
 * Uses {@link RepoContext} to match the target repo's package, base class, and imports.
 */
@Slf4j
@Component
public class MobileTestRunner {

    public String generateCode(BddScenario.Scenario scenario,
                               BddScenario parent,
                               RepoContext context) {
        log.debug("[MobileTestRunner] Generating Mobile test for '{}' (context={})",
                scenario.getTitle(), context.isContextAvailable());

        String pkg       = context.effectivePackage("nz.co.eroad.qaisystem.generated.tests.mobile");
        String className = toClassName(scenario.getTitle());

        return """
                %s
                package %s;

                import io.appium.java_client.AppiumDriver;
                import io.appium.java_client.android.AndroidDriver;
                import org.junit.jupiter.api.*;
                import org.openqa.selenium.remote.DesiredCapabilities;
                import java.net.MalformedURLException;
                import java.net.URL;
                import static org.assertj.core.api.Assertions.assertThat;
                %s
                /**
                 * Auto-generated Mobile test for PR : %s
                 * Scenario                         : %s
                 * Context repo                     : %s
                 */
                public class %sMobileTest%s {

                    private AppiumDriver driver;

                    @BeforeEach
                    void setUp() throws MalformedURLException {
                        DesiredCapabilities caps = new DesiredCapabilities();
                        caps.setCapability("platformName",   "Android");
                        caps.setCapability("deviceName",     "emulator-5554");
                        caps.setCapability("app",            "/path/to/app.apk"); // TODO: configure
                        caps.setCapability("automationName", "UiAutomator2");
                        driver = new AndroidDriver(new URL("http://localhost:4723/wd/hub"), caps);
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
                        %s

                        // THEN
                        assertThat(driver.getPageSource()).isNotNull();
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
