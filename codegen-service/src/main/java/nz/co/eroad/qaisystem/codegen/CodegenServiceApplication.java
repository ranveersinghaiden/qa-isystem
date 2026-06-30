package nz.co.eroad.qaisystem.codegen;

import nz.co.eroad.qaisystem.oneshot.OneShotArgs;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Phase 5-6: Code generation, test stabilisation, and test PR creation.
 * Consumes: TestScriptsQueue
 * Produces: test PRs on GitHub
 * Port: 8083
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class CodegenServiceApplication {
    public static void main(String[] args) {
        if (OneShotArgs.isOneShot(args)) {
            // One-shot scale-to-zero mode: activate the `oneshot` profile, disable the web server,
            // and let CodegenOneShotRunner do one thing and System.exit. Normal path is unchanged.
            new SpringApplicationBuilder(CodegenServiceApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("oneshot")
                    .run(args);
        } else {
            SpringApplication.run(CodegenServiceApplication.class, args);
        }
    }
}
