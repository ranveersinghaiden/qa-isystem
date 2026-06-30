package nz.co.eroad.qaisystem.strategy;

import nz.co.eroad.qaisystem.oneshot.OneShotArgs;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Phase 2+: AI strategy decisions, BDD generation, codegen, test stabilization.
 * Consumes: ImpactResultsQueue
 * Produces: TestScriptsQueue, BDD PRs
 * Port 8082.
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class StrategyServiceApplication {
    public static void main(String[] args) {
        if (OneShotArgs.isOneShot(args)) {
            // One-shot scale-to-zero mode: activate the `oneshot` profile, disable the web server,
            // and let StrategyOneShotRunner do one thing and System.exit. Normal path is unchanged.
            new SpringApplicationBuilder(StrategyServiceApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("oneshot")
                    .run(args);
        } else {
            SpringApplication.run(StrategyServiceApplication.class, args);
        }
    }
}

