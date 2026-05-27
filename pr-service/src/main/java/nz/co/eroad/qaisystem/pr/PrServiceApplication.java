package nz.co.eroad.qaisystem.pr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 1 entry point: accepts PR webhook events and publishes to FeatureUpdatesQueue.
 * Port 8080.  Run with: java -jar pr-service.jar
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class PrServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrServiceApplication.class, args);
    }
}

