package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.CoverageReport;
import nz.co.eroad.qaisystem.model.CoverageReport.CoverageLevel;
import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Determines whether existing tests already cover the impacted areas.
 *
 * This is the "intelligence layer" that transforms the system from a
 * blind AI generator into an intelligent QA layer that only generates
 * tests where they are actually missing.
 */
@Slf4j
@Service
public class TestCoverageService {

    private static final double GOOD_THRESHOLD    = 0.8;
    private static final double PARTIAL_THRESHOLD = 0.2;

    /**
     * Assess test coverage for the set of changed components.
     *
     * @param components impacted components from DependencyGraph
     * @param diffs      all file diffs from the PR
     * @return CoverageReport with coverage ratio, level, and missing areas
     */
    public CoverageReport assess(List<ImpactedComponent> components,
                                 List<GitDiff> diffs) {

        long srcFiles  = diffs.stream().filter(d -> !d.isTestFile()).count();
        long testFiles = diffs.stream().filter(GitDiff::isTestFile).count();

        List<String> existingTestFiles = diffs.stream()
                .filter(GitDiff::isTestFile)
                .map(GitDiff::getFilePath)
                .collect(Collectors.toList());

        // Which non-test components have NO matching test file in the diff?
        List<String> missingCoverageAreas = components.stream()
                .filter(c -> c.getType() != ImpactedComponent.ComponentType.TEST)
                .filter(c -> !hasMatchingTest(c.getComponentName(), diffs))
                .map(ImpactedComponent::getComponentName)
                .collect(Collectors.toList());

        double ratio = srcFiles > 0 ? (double) testFiles / srcFiles : 0.0;
        CoverageLevel level = toLevel(ratio);

        CoverageReport report = CoverageReport.builder()
                .level(level)
                .coverageRatio(ratio)
                .existingTestFiles(existingTestFiles)
                .missingCoverageAreas(missingCoverageAreas)
                .requiresNewTests(!missingCoverageAreas.isEmpty())
                .build();

        log.info("[TestCoverageService] Coverage: ratio={} level={} missing={}",
                String.format("%.2f", ratio), level, missingCoverageAreas.size());

        return report;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Check if a component has a corresponding test file in the diff set.
     * Matches on conventional naming: AuthService → AuthServiceTest / AuthServiceSpec.
     */
    private boolean hasMatchingTest(String componentName, List<GitDiff> diffs) {
        String base = componentName.replace("Test", "").replace("Spec", "");
        return diffs.stream()
                .filter(GitDiff::isTestFile)
                .anyMatch(d -> d.getFilePath().contains(base + "Test")
                        || d.getFilePath().contains(base + "Spec")
                        || d.getFilePath().contains(base + "IT"));
    }

    private CoverageLevel toLevel(double ratio) {
        if (ratio >= GOOD_THRESHOLD)    return CoverageLevel.GOOD;
        if (ratio >= PARTIAL_THRESHOLD) return CoverageLevel.PARTIAL;
        return CoverageLevel.NONE;
    }
}

