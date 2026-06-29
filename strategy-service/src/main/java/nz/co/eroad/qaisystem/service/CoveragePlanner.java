package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.CoverageReport;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent.ComponentType;
import nz.co.eroad.qaisystem.model.ScenarioClass;
import nz.co.eroad.qaisystem.model.ScenarioMatrix;
import nz.co.eroad.qaisystem.model.ScenarioMatrix.Cell;
import nz.co.eroad.qaisystem.model.ScenarioMatrix.CellStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Deterministic coverage planner. Turns the binary <em>has-a-test</em> signal from
 * {@link E2ECoverageAnalyzer} into a <em>tested-well</em> matrix: for every impacted
 * capability it enumerates the {@link ScenarioClass}es required by the PR's
 * {@link ChangeType}s, then marks each cell COVERED or PLANNED. PLANNED cells are
 * the gaps the BDD generator must fill — closing the open-ended "write scenarios"
 * loop that left coverage to LLM discretion.
 *
 * <p>Class selection is fully deterministic (no AI). Marking is conservative:
 * with only component-level coverage data, a covered capability is credited the
 * {@link ScenarioClass#HAPPY_PATH} cell and every other required class stays PLANNED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoveragePlanner {

    private final RejectionLedger rejectionLedger;

    private static final Set<ComponentType> TESTABLE = Set.of(
            ComponentType.CONTROLLER, ComponentType.SERVICE,
            ComponentType.REPOSITORY, ComponentType.CONFIG);

    private static final Map<ChangeType, List<ScenarioClass>> REQUIRED = Map.ofEntries(
            Map.entry(ChangeType.NEW_FEATURE, List.of(
                    ScenarioClass.HAPPY_PATH, ScenarioClass.ALTERNATE, ScenarioClass.BOUNDARY,
                    ScenarioClass.NEGATIVE, ScenarioClass.AUTH, ScenarioClass.ERROR)),
            Map.entry(ChangeType.API_CHANGE, List.of(
                    ScenarioClass.HAPPY_PATH, ScenarioClass.NEGATIVE, ScenarioClass.AUTH,
                    ScenarioClass.COMPAT, ScenarioClass.ERROR)),
            Map.entry(ChangeType.BUG_FIX, List.of(
                    ScenarioClass.REGRESSION, ScenarioClass.NEGATIVE, ScenarioClass.BOUNDARY)),
            Map.entry(ChangeType.REFACTORING, List.of(
                    ScenarioClass.REGRESSION, ScenarioClass.HAPPY_PATH)),
            Map.entry(ChangeType.CONFIGURATION_CHANGE, List.of(ScenarioClass.REGRESSION)),
            Map.entry(ChangeType.DEPENDENCY_UPDATE, List.of(
                    ScenarioClass.REGRESSION, ScenarioClass.COMPAT)),
            Map.entry(ChangeType.DATABASE_CHANGE, List.of(
                    ScenarioClass.DATA_INTEGRITY, ScenarioClass.BOUNDARY, ScenarioClass.ERROR)),
            Map.entry(ChangeType.SECURITY_FIX, List.of(
                    ScenarioClass.AUTH, ScenarioClass.NEGATIVE, ScenarioClass.ERROR)),
            Map.entry(ChangeType.PERFORMANCE_IMPROVEMENT, List.of(
                    ScenarioClass.REGRESSION, ScenarioClass.BOUNDARY)),
            Map.entry(ChangeType.BREAKING_CHANGE, List.of(
                    ScenarioClass.REGRESSION, ScenarioClass.COMPAT, ScenarioClass.NEGATIVE)));

    private static final List<ScenarioClass> DEFAULT_CLASSES = List.of(
            ScenarioClass.HAPPY_PATH, ScenarioClass.NEGATIVE);

    /**
     * Builds the capability × scenario-class matrix for a PR.
     *
     * @param envelope impact envelope (drives capabilities + change types)
     * @param coverage component-level coverage from {@link E2ECoverageAnalyzer};
     *                 covered components credit their HAPPY_PATH cell
     * @return matrix with PLANNED cells = scenario gaps to fill
     */
    public ScenarioMatrix plan(ImpactEnvelope envelope, CoverageReport coverage) {
        List<ScenarioClass> classes = requiredClasses(envelope.getDetectedChangeTypes());
        List<String> capabilities = capabilities(envelope);
        Set<String> tested = coverage != null && coverage.getTestedComponents() != null
                ? new HashSet<>(coverage.getTestedComponents()) : Set.of();

        List<Cell> cells = new ArrayList<>();
        for (String cap : capabilities) {
            boolean covered = tested.contains(cap);
            Set<ScenarioClass> required = new LinkedHashSet<>(classes);
            required.addAll(rejectionLedger.recurringClasses(cap));
            for (ScenarioClass cls : required) {
                CellStatus status = covered && cls == ScenarioClass.HAPPY_PATH
                        ? CellStatus.COVERED : CellStatus.PLANNED;
                cells.add(new Cell(cap, cls, status));
            }
        }

        ScenarioMatrix matrix = new ScenarioMatrix(cells);
        log.info("[CoveragePlanner] PR '{}' matrix: {} capabilities × {} classes → {} gaps, recall={}",
                envelope.getPrId(), capabilities.size(), classes.size(), matrix.gaps().size(),
                String.format("%.2f", matrix.recall()));
        return matrix;
    }

    private List<ScenarioClass> requiredClasses(List<ChangeType> changeTypes) {
        if (changeTypes == null || changeTypes.isEmpty()) return DEFAULT_CLASSES;
        Set<ScenarioClass> set = new LinkedHashSet<>();
        changeTypes.forEach(ct -> set.addAll(REQUIRED.getOrDefault(ct, DEFAULT_CLASSES)));
        return List.copyOf(set);
    }

    private List<String> capabilities(ImpactEnvelope envelope) {
        List<ImpactedComponent> all = Optional.ofNullable(envelope.getImpactedComponents())
                .orElse(List.of());
        List<String> testable = all.stream()
                .filter(c -> TESTABLE.contains(c.getType()))
                .map(ImpactedComponent::getComponentName).distinct().toList();
        if (!testable.isEmpty()) return testable;
        if (!all.isEmpty()) return all.stream().map(ImpactedComponent::getComponentName).distinct().toList();
        return List.of(envelope.getPrId() == null ? "system" : envelope.getPrId());
    }
}
