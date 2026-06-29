package nz.co.eroad.qaisystem.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.config.CoveragePlanMonitorProperties;
import nz.co.eroad.qaisystem.model.ScenarioClass;
import nz.co.eroad.qaisystem.model.ScenarioMatrix;
import nz.co.eroad.qaisystem.model.ScenarioMatrix.Cell;
import nz.co.eroad.qaisystem.model.ScenarioMatrix.CellStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Zero-mock tests for the off-by-default coverage-plan monitor. */
class CoveragePlanMonitorTest {

    private static ScenarioMatrix matrix() {
        return new ScenarioMatrix(List.of(
                new Cell("AuthService", ScenarioClass.HAPPY_PATH, CellStatus.COVERED),
                new Cell("AuthService", ScenarioClass.NEGATIVE, CellStatus.PLANNED),
                new Cell("AuthService", ScenarioClass.AUTH, CellStatus.PLANNED)));
    }

    private static CoveragePlanMonitorProperties props(Path dir) {
        CoveragePlanMonitorProperties p = new CoveragePlanMonitorProperties();
        p.setEnabled(true);
        p.setDir(dir.toString());
        return p;
    }

    private static Path index(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().equals("coverage-plan-index.jsonl"))
                    .findFirst().orElseThrow();
        }
    }

    @Test
    void record_writesPlanFileAndIndex(@TempDir Path dir) throws Exception {
        new CoveragePlanMonitor(props(dir), new ObjectMapper()).record(
                "PR-1", List.of("BUG_FIX"), List.of("AuthService"),
                List.of(ScenarioClass.NEGATIVE, ScenarioClass.AUTH), matrix());

        try (Stream<Path> s = Files.walk(dir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("plan-")
                    && p.toString().endsWith(".json")), "plan-*.json written");
        }
        String line = Files.readString(index(dir));
        assertTrue(line.contains("PR-1"));
        assertTrue(line.contains("\"gaps\":2"));
        assertTrue(line.contains("\"cellCount\":3"));
    }

    @Test
    void nullMatrix_writesNothing(@TempDir Path dir) {
        new CoveragePlanMonitor(props(dir), new ObjectMapper())
                .record("PR-1", List.of(), List.of(), List.of(), null);
        assertFalse(Files.exists(dir.resolve("coverage-plan-index.jsonl")));
    }

    @Test
    void excludeCells_recordsCountsOnly(@TempDir Path dir) throws Exception {
        CoveragePlanMonitorProperties p = props(dir);
        p.setIncludeCells(false);
        new CoveragePlanMonitor(p, new ObjectMapper())
                .record("PR-2", List.of("BUG_FIX"), List.of("AuthService"), List.of(), matrix());
        assertTrue(Files.readString(index(dir)).contains("\"cellCount\":0"));
    }

    @Test
    void traversalPrId_staysUnderRoot(@TempDir Path dir) throws Exception {
        new CoveragePlanMonitor(props(dir), new ObjectMapper())
                .record("../../../etc/x", List.of(), List.of("c"), List.of(), matrix());
        try (Stream<Path> s = Files.walk(dir)) {
            assertTrue(s.allMatch(p -> p.startsWith(dir)), "all artifacts stay under monitor root");
        }
    }

    @Test
    void neverThrows(@TempDir Path dir) {
        var m = new CoveragePlanMonitor(props(dir), new ObjectMapper());
        assertDoesNotThrow(() -> m.record(null, null, null, null, matrix()));
    }
}
