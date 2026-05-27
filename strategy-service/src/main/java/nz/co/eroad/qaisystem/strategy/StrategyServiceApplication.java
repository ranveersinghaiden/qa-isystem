package nz.co.eroad.qaisystem.strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 2+: AI strategy decisions, BDD generation, codegen, test stabilization.
 * Consumes: ImpactResultsQueue
 * Produces: TestScriptsQueue, BDD PRs
 * Port 8082.
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class StrategyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(StrategyServiceApplication.class, args);
    }
}

