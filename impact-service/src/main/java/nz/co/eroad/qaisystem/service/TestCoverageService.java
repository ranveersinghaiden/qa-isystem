package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.CoverageReport;
import nz.co.eroad.qaisystem.model.CoverageReport.CoverageLevel;
import nz.co.eroad.qaisystem.model.CoverageReport.CoverageSource;
import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent.ComponentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Identifies which impacted components require integration or E2E test coverage,
 * based solely on their component type and the nature of changes in the diff.
 *
 * <p><b>This service does NOT check the test repository.</b>
 * It has no knowledge of what tests already exist.
 * The real coverage assessment (GOOD / PARTIAL / NONE) is performed downstream by
 * {@code E2ECoverageAnalyzer} in strategy-service, which has access to the cloned
 * test repo. This service sets {@code level = UNKNOWN} and populates:
 * <ul>
 *   <li>{@code untestedComponents} — components whose type warrants integration testing</li>
 *   <li>{@code requiredTestTypes} — test categories needed (API, INTEGRATION, E2E)</li>
 * </ul>
 */
@Slf4j
@Service
public class TestCoverageService {

    /**
     * Component types that require integration or E2E tests.
     * MODEL and UTILITY are excluded — they are covered by unit tests in the PR itself.
     */
    private static final Set<ComponentType> INTEGRATION_TEST_REQUIRED = Set.of(
            ComponentType.CONTROLLER,  // → API / E2E tests
            ComponentType.SERVICE,     // → Integration tests
            ComponentType.REPOSITORY,  // → Integration tests (real DB)
            ComponentType.CONFIG       // → Smoke tests
    );

    /**
     * Assesses which changed components need integration/E2E test coverage.
     *
     * @param components impacted components from DependencyGraph
     * @param diffs      all file diffs from the PR (used for change-type hints)
     * @return CoverageReport with untestedComponents and requiredTestTypes;
     *         level=UNKNOWN until strategy-service performs the real repo scan
     */
    public CoverageReport assess(List<ImpactedComponent> components,
                                 List<GitDiff> diffs) {

        // Components that warrant integration/E2E coverage by their type
        List<String> needsIntegrationTest = components.stream()
                .filter(c -> INTEGRATION_TEST_REQUIRED.contains(c.getType()))
                .map(ImpactedComponent::getComponentName)
                .collect(Collectors.toList());

        // Determine which test categories are needed
        List<String> requiredTestTypes = resolveRequiredTestTypes(components, diffs);

        CoverageReport report = CoverageReport.builder()
                .source(CoverageSource.UNKNOWN)
                .level(CoverageLevel.UNKNOWN)         // real level determined by E2ECoverageAnalyzer
                .coverageRatio(0.0)                   // unknown until repo scan
                .testedComponents(List.of())          // unknown until repo scan
                .untestedComponents(needsIntegrationTest)
                .requiredTestTypes(requiredTestTypes)
                .existingTestFiles(List.of())         // unknown until repo scan
                .requiresNewTests(!needsIntegrationTest.isEmpty())
                .build();

        log.info("[TestCoverageService] {} components need integration/E2E coverage: {} | types: {}",
                needsIntegrationTest.size(), needsIntegrationTest, requiredTestTypes);

        return report;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Determines what test categories are needed based on component types and diff content.
     * CONTROLLER → API tests. SERVICE/REPOSITORY → INTEGRATION tests.
     * Database patterns in diff → INTEGRATION tests. Any E2E-triggering scenario → E2E.
     */
    private List<String> resolveRequiredTestTypes(List<ImpactedComponent> components,
                                                   List<GitDiff> diffs) {
        Set<String> types = new LinkedHashSet<>();

        boolean hasControllers = components.stream()
                .anyMatch(c -> c.getType() == ComponentType.CONTROLLER);
        boolean hasServices = components.stream()
                .anyMatch(c -> c.getType() == ComponentType.SERVICE);
        boolean hasRepos = components.stream()
                .anyMatch(c -> c.getType() == ComponentType.REPOSITORY);

        if (hasControllers)            types.add("API");
        if (hasServices || hasRepos)   types.add("INTEGRATION");

        // Database migration changes always need integration tests
        boolean hasDbChanges = diffs.stream()
                .flatMap(d -> d.getHunks() != null ? d.getHunks().stream() : java.util.stream.Stream.empty())
                .flatMap(h -> h.getLines() != null ? h.getLines().stream() : java.util.stream.Stream.empty())
                .anyMatch(l -> l.getContent() != null && l.getContent()
                        .matches("(?i).*(migration|flyway|liquibase|ALTER TABLE|CREATE TABLE|DROP TABLE).*"));
        if (hasDbChanges) types.add("INTEGRATION");

        // Security changes warrant E2E tests (auth flows must be tested end-to-end)
        boolean hasSecurityChanges = diffs.stream()
                .flatMap(d -> d.getHunks() != null ? d.getHunks().stream() : java.util.stream.Stream.empty())
                .flatMap(h -> h.getLines() != null ? h.getLines().stream() : java.util.stream.Stream.empty())
                .anyMatch(l -> l.getContent() != null && l.getContent()
                        .matches("(?i).*(security|auth|oauth|jwt|token|permission|role).*"));
        if (hasSecurityChanges) types.add("E2E");

        if (types.isEmpty()) types.add("API"); // safe default
        return new ArrayList<>(types);
    }
}
