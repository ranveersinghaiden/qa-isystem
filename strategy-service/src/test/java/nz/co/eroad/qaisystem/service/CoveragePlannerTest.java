package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.CoverageReport;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent.ComponentType;
import nz.co.eroad.qaisystem.model.ScenarioClass;
import nz.co.eroad.qaisystem.model.ScenarioMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Zero-mock tests for the deterministic coverage planner. */
class CoveragePlannerTest {

    private final CoveragePlanner planner = new CoveragePlanner(new NoOpRejectionLedger());

    private static ImpactEnvelope envelope(ChangeType ct, String component) {
        return ImpactEnvelope.builder()
                .prId("PR-1")
                .detectedChangeTypes(List.of(ct))
                .impactedComponents(List.of(ImpactedComponent.builder()
                        .componentName(component).type(ComponentType.SERVICE).build()))
                .build();
    }

    @Test
    void newFeatureUntested_allCellsPlanned() {
        ScenarioMatrix m = planner.plan(envelope(ChangeType.NEW_FEATURE, "AuthService"),
                CoverageReport.builder().testedComponents(List.of()).build());
        assertEquals(6, m.plannedCount());
        assertEquals(0, m.coveredCount());
        assertEquals(0.0, m.recall());
        assertTrue(m.gaps().stream().anyMatch(c -> c.scenarioClass() == ScenarioClass.NEGATIVE));
    }

    @Test
    void coveredComponent_creditsHappyPath() {
        ScenarioMatrix m = planner.plan(envelope(ChangeType.NEW_FEATURE, "AuthService"),
                CoverageReport.builder().testedComponents(List.of("AuthService")).build());
        assertEquals(1, m.coveredCount());
        assertTrue(m.recall() > 0.0);
    }

    @Test
    void recurringRejection_forcesExtraClass() {
        CoveragePlanner withLedger = new CoveragePlanner(new RejectionLedger() {
            public void recordRejection(String c, ScenarioClass s) {}
            public Set<ScenarioClass> recurringClasses(String c) { return Set.of(ScenarioClass.DATA_INTEGRITY); }
        });
        ScenarioMatrix m = withLedger.plan(envelope(ChangeType.BUG_FIX, "AuthService"),
                CoverageReport.builder().testedComponents(List.of()).build());
        assertTrue(m.gaps().stream().anyMatch(c -> c.scenarioClass() == ScenarioClass.DATA_INTEGRITY));
    }
}
