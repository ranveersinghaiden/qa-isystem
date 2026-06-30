package nz.co.eroad.qaisystem.impact;

import nz.co.eroad.qaisystem.oneshot.OneShotArgs;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Phase 1 service: deterministic impact analysis, no AI.
 * Consumes: FeatureUpdatesQueue
 * Produces: ImpactResultsQueue
 * Port 8081.
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class ImpactServiceApplication {
    public static void main(String[] args) {
        if (OneShotArgs.isOneShot(args)) {
            // One-shot scale-to-zero mode: activate the `oneshot` profile, disable the web server,
            // and let ImpactOneShotRunner do one thing and System.exit. Normal path is unchanged.
            new SpringApplicationBuilder(ImpactServiceApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("oneshot")
                    .run(args);
        } else {
            SpringApplication.run(ImpactServiceApplication.class, args);
        }
    }
}

