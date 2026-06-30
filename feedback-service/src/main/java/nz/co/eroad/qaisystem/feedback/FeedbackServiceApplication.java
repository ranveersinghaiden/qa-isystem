package nz.co.eroad.qaisystem.feedback;

import nz.co.eroad.qaisystem.oneshot.OneShotArgs;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Phase 7: AI-native feedback loop — handles rejected BDD and test PRs.
 * Consumes: FeedbackQueue
 * Produces: revised BDD PRs, revised test PRs, product expert update PRs
 * Port: 8084
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class FeedbackServiceApplication {
    public static void main(String[] args) {
        if (OneShotArgs.isOneShot(args)) {
            // One-shot scale-to-zero mode: activate the `oneshot` profile, disable the web server,
            // and let FeedbackOneShotRunner do one thing and System.exit. Normal path is unchanged.
            new SpringApplicationBuilder(FeedbackServiceApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("oneshot")
                    .run(args);
        } else {
            SpringApplication.run(FeedbackServiceApplication.class, args);
        }
    }
}
