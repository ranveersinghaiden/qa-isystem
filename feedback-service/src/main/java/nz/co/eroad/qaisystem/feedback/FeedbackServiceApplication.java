package nz.co.eroad.qaisystem.feedback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 7: AI-native feedback loop — handles rejected BDD and test PRs.
 * Consumes: FeedbackQueue
 * Produces: revised BDD PRs, revised test PRs, product expert update PRs
 * Port: 8084
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class FeedbackServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FeedbackServiceApplication.class, args);
    }
}
