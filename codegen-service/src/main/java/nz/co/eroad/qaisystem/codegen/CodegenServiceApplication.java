package nz.co.eroad.qaisystem.codegen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 5-6: Code generation, test stabilisation, and test PR creation.
 * Consumes: TestScriptsQueue
 * Produces: test PRs on GitHub
 * Port: 8083
 */
@SpringBootApplication(scanBasePackages = "nz.co.eroad.qaisystem")
public class CodegenServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodegenServiceApplication.class, args);
    }
}
