package nz.co.eroad.qaisystem.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Integration / E2E test coverage assessment for impacted components.
 *
 * <p>Two-phase lifecycle:
 * <ol>
 *   <li><b>impact-service</b> produces this with {@code source=UNKNOWN} and populates
 *       {@code untestedComponents} with every component whose type warrants integration
 *       testing (CONTROLLER, SERVICE, REPOSITORY, CONFIG). It does NOT check the test repo —
 *       it cannot, it has no access. {@code level} is set to {@code UNKNOWN}.</li>
 *   <li><b>strategy-service / E2ECoverageAnalyzer</b> replaces this with a real assessment
 *       by scanning the cloned test repo for integration/E2E test files that reference the
 *       impacted components. {@code source} becomes {@code REPO_SCAN} and {@code level}
 *       reflects actual coverage found.</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoverageReport {

    /** Where the coverage data came from. */
    private CoverageSource source;

    /** Actual coverage level — UNKNOWN until test repo is scanned. */
    private CoverageLevel level;

    /**
     * Ratio of covered components (0.0–1.0).
     * Meaningful only when source=REPO_SCAN.
     */
    private double coverageRatio;

    /** Components that HAVE integration/E2E test coverage in the test repo. */
    private List<String> testedComponents;

    /**
     * Components that NEED integration/E2E tests but have NONE found in the test repo.
     * Populated by impact-service (components whose type warrants integration testing)
     * and refined by E2ECoverageAnalyzer after repo scan.
     */
    private List<String> untestedComponents;

    /**
     * Test types required for the changed components, e.g. ["API", "INTEGRATION", "E2E"].
     * Derived from component types and detected change types.
     */
    private List<String> requiredTestTypes;

    /**
     * Integration/E2E test files found in the test repo that cover impacted components.
     * Empty when source=UNKNOWN (no repo configured).
     */
    private List<String> existingTestFiles;

    /** True when there are components without integration/E2E test coverage. */
    private boolean requiresNewTests;

    public enum CoverageLevel {
        GOOD,    // all testable components have integration test coverage
        PARTIAL, // some components covered, some not
        NONE,    // no integration test coverage found for any testable component
        UNKNOWN  // test repo not configured — cannot determine coverage
    }

    public enum CoverageSource {
        REPO_SCAN, // E2ECoverageAnalyzer scanned the cloned test repo
        UNKNOWN    // no test repo available; assessment based on component types only
    }
}

