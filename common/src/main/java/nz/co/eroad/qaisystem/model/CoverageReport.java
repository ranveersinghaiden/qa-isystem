package nz.co.eroad.qaisystem.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Test coverage assessment for a set of impacted components.
 * Produced by TestCoverageService and stored in ImpactEnvelope.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoverageReport {

    private CoverageLevel level;      // GOOD / PARTIAL / NONE
    private double coverageRatio;     // 0.0–1.0 (testFiles / srcFiles)
    private List<String> existingTestFiles;
    private List<String> missingCoverageAreas; // component names with no test
    private boolean requiresNewTests;

    public enum CoverageLevel {
        GOOD,    // ≥ 80 % coverage
        PARTIAL, // 20–79 %
        NONE     // < 20 %
    }
}

