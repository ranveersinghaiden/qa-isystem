package nz.co.eroad.qaisystem.impact;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 1 service: deterministic impact analysis, no AI.
 * Consumes: FeatureUpdatesQueue
 * Produces: ImpactResultsQueue
 * Port 8081.
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class ImpactServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ImpactServiceApplication.class, args);
    }
}

