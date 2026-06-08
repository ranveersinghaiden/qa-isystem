package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Generates Appium / JUnit 5 test code from a BDD scenario.
 * Uses {@link RepoContext} to match the target repo's package, base class, and imports.
 */
@Slf4j
@Component
public class MobileTestRunner {

    @Value("${aiqa.mobile.app-path:}")
    private String mobileAppPath;

    @Value("${aiqa.mobile.device-name:emulator-5554}")
    private String mobileDeviceName;

    @Value("${aiqa.mobile.platform:Android}")
    private String mobilePlatform;

    @Value("${aiqa.mobile.appium-url:http://localhost:4723/wd/hub}")
    private String appiumUrl;

    /** Generates an Appium/JUnit 5 test class source string for the given BDD scenario. */
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
                        caps.setCapability("platformName",   "%s");
                        caps.setCapability("deviceName",     "%s");
                        caps.setCapability("app",            "%s");
                        caps.setCapability("automationName", "UiAutomator2");
                        driver = new AndroidDriver(new URL("%s"), caps);
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
                mobilePlatform,
                mobileDeviceName,
                mobileAppPath,
                appiumUrl,
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
