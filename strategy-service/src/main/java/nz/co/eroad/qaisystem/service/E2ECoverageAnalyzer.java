package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.CoverageReport;
import nz.co.eroad.qaisystem.model.CoverageReport.CoverageLevel;
import nz.co.eroad.qaisystem.model.CoverageReport.CoverageSource;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent.ComponentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Determines whether impacted components have integration/E2E test coverage in the
 * target test repository, by cross-referencing the impact envelope against the
 * coverage index built by {@link RepoContextService}.
 *
 * <p>This is the service that produces a <em>real</em> coverage assessment
 * ({@code GOOD / PARTIAL / NONE}). The upstream {@code TestCoverageService} in
 * impact-service only sets {@code level = UNKNOWN} because it has no test repo access.
 *
 * <p><b>Coverage logic:</b>
 * <ol>
 *   <li>Filter impacted components to those that warrant integration tests
 *       (CONTROLLER, SERVICE, REPOSITORY, CONFIG).</li>
 *   <li>For each, check whether the coverage index (from the cloned test repo)
 *       contains any integration/E2E test file that references that component.</li>
 *   <li>Compute {@code testedComponents}, {@code untestedComponents}, and {@code level}.</li>
 *   <li>When no test repo is configured ({@code coverageIndex} is empty), fall back to
 *       the impact-service assessment ({@code level = UNKNOWN}) with a note that the
 *       repo scan could not be performed.</li>
 * </ol>
 *
 * <p>Called by {@link nz.co.eroad.qaisystem.agent.StrategyAgent} before making its
 * CREATE/UPDATE/SKIP decision so the decision reflects actual test coverage state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class E2ECoverageAnalyzer {

    private final RepoContextService repoContextService;

    /** Component types that require integration/E2E tests — must match TestCoverageService. */
    private static final Set<ComponentType> REQUIRES_INTEGRATION_TEST = Set.of(
            ComponentType.CONTROLLER,
            ComponentType.SERVICE,
            ComponentType.REPOSITORY,
            ComponentType.CONFIG
    );

    /**
     * Analyzes integration/E2E test coverage for all impacted components in the envelope.
     *
     * @param envelope the impact envelope produced by impact-service
     * @return a {@link CoverageReport} with real coverage data from the test repo,
     *         or {@code level=UNKNOWN} if no test repo is configured
     */
    public CoverageReport analyze(ImpactEnvelope envelope) {
        Map<String, List<String>> index = repoContextService.getCoverageIndex();

        // Components that need integration testing
        List<ImpactedComponent> testableComponents = Optional
                .ofNullable(envelope.getImpactedComponents())
                .orElse(List.of())
                .stream()
                .filter(c -> REQUIRES_INTEGRATION_TEST.contains(c.getType()))
                .collect(Collectors.toList());

        if (testableComponents.isEmpty()) {
            log.info("[E2ECoverageAnalyzer] PR '{}' — no integration-testable components changed; coverage=GOOD",
                    envelope.getPrId());
            return CoverageReport.builder()
                    .source(index.isEmpty() ? CoverageSource.UNKNOWN : CoverageSource.REPO_SCAN)
                    .level(CoverageLevel.GOOD)
                    .coverageRatio(1.0)
                    .testedComponents(List.of())
                    .untestedComponents(List.of())
                    .requiredTestTypes(List.of())
                    .existingTestFiles(List.of())
                    .requiresNewTests(false)
                    .build();
        }

        // No test repo available — propagate the UNKNOWN assessment from impact-service
        if (index.isEmpty()) {
            CoverageReport upstream = envelope.getCoverageReport();
            log.info("[E2ECoverageAnalyzer] PR '{}' — no test repo configured; " +
                    "propagating UNKNOWN coverage (components pending scan: {})",
                    envelope.getPrId(),
                    upstream != null ? upstream.getUntestedComponents() : testableComponents.stream()
                            .map(ImpactedComponent::getComponentName).collect(Collectors.toList()));

            List<String> pending = upstream != null && upstream.getUntestedComponents() != null
                    ? upstream.getUntestedComponents()
                    : testableComponents.stream()
                            .map(ImpactedComponent::getComponentName)
                            .collect(Collectors.toList());

            List<String> requiredTypes = upstream != null && upstream.getRequiredTestTypes() != null
                    ? upstream.getRequiredTestTypes()
                    : resolveRequiredTypes(testableComponents);

            return CoverageReport.builder()
                    .source(CoverageSource.UNKNOWN)
                    .level(CoverageLevel.UNKNOWN)
                    .coverageRatio(0.0)
                    .testedComponents(List.of())
                    .untestedComponents(pending)
                    .requiredTestTypes(requiredTypes)
                    .existingTestFiles(List.of())
                    .requiresNewTests(true)
                    .build();
        }

        // Repo is available — perform real coverage analysis
        List<String> covered   = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        Set<String>  coveringTestFiles = new LinkedHashSet<>();

        for (ImpactedComponent comp : testableComponents) {
            List<String> tests = index.getOrDefault(comp.getComponentName(), List.of());
            if (!tests.isEmpty()) {
                covered.add(comp.getComponentName());
                coveringTestFiles.addAll(tests);
                log.debug("[E2ECoverageAnalyzer] '{}' covered by: {}", comp.getComponentName(), tests);
            } else {
                uncovered.add(comp.getComponentName());
                log.debug("[E2ECoverageAnalyzer] '{}' has NO integration/E2E test coverage",
                        comp.getComponentName());
            }
        }

        // Derive coverage level
        CoverageLevel level;
        if (uncovered.isEmpty()) {
            level = CoverageLevel.GOOD;
        } else if (!covered.isEmpty() && covered.size() >= uncovered.size()) {
            level = CoverageLevel.PARTIAL;
        } else {
            level = CoverageLevel.NONE;
        }

        double ratio = testableComponents.isEmpty() ? 1.0
                : (double) covered.size() / testableComponents.size();

        CoverageReport upstreamReport = envelope.getCoverageReport();
        List<String> requiredTypes = upstreamReport != null && upstreamReport.getRequiredTestTypes() != null
                ? upstreamReport.getRequiredTestTypes()
                : resolveRequiredTypes(testableComponents);

        log.info("[E2ECoverageAnalyzer] PR '{}' — coverage={} ({}/{} components covered) " +
                "uncovered={} requiredTypes={}",
                envelope.getPrId(), level, covered.size(), testableComponents.size(),
                uncovered, requiredTypes);

        return CoverageReport.builder()
                .source(CoverageSource.REPO_SCAN)
                .level(level)
                .coverageRatio(ratio)
                .testedComponents(covered)
                .untestedComponents(uncovered)
                .requiredTestTypes(requiredTypes)
                .existingTestFiles(new ArrayList<>(coveringTestFiles))
                .requiresNewTests(!uncovered.isEmpty())
                .build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Derives required test types from component types when the upstream report
     * has no {@code requiredTestTypes} (e.g. in tests or when impact-service is bypassed).
     */
    private List<String> resolveRequiredTypes(List<ImpactedComponent> components) {
        Set<String> types = new LinkedHashSet<>();
        components.forEach(c -> {
            switch (c.getType()) {
                case CONTROLLER -> types.add("API");
                case SERVICE, REPOSITORY -> types.add("INTEGRATION");
                case CONFIG -> types.add("SMOKE");
                default -> {}
            }
        });
        if (types.isEmpty()) types.add("API");
        return new ArrayList<>(types);
    }
}

