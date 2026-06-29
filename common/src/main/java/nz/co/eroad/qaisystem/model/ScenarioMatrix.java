package nz.co.eroad.qaisystem.model;

import java.util.List;

/**
 * Capability × {@link ScenarioClass} coverage matrix. Each cell states whether a
 * capability is already COVERED by an existing test of that class, must still be
 * PLANNED, or is NA for the change. Produced by the strategy-service coverage
 * planner and surfaced to the BDD generator so it fills only PLANNED gaps.
 *
 * <p>{@code recall = covered / (covered + planned)} — fraction of required cells
 * already tested. {@code gaps()} are the PLANNED cells the generator must fill.
 */
public record ScenarioMatrix(List<Cell> cells) {

    public ScenarioMatrix {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }

    public enum CellStatus { COVERED, PLANNED, NA }

    /** One capability/scenario-class intersection. */
    public record Cell(String capability, ScenarioClass scenarioClass, CellStatus status) {}

    public List<Cell> gaps() {
        return cells.stream().filter(c -> c.status() == CellStatus.PLANNED).toList();
    }

    public long coveredCount() {
        return cells.stream().filter(c -> c.status() == CellStatus.COVERED).count();
    }

    public long plannedCount() {
        return cells.stream().filter(c -> c.status() == CellStatus.PLANNED).count();
    }

    /** Required cells already covered, 0.0–1.0; 1.0 when nothing is required. */
    public double recall() {
        long required = coveredCount() + plannedCount();
        return required == 0 ? 1.0 : (double) coveredCount() / required;
    }
}
