package nz.co.eroad.qaisystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the off-by-default "Coverage Plan" monitor.
 *
 * <p>Bound from {@code aiqa.coverage-plan.monitor.*}. When {@link #enabled} is {@code false}
 * (the default) no {@code CoveragePlanMonitor} bean is created and {@code CoveragePlanner}
 * behaves exactly as before — zero behaviour change.
 *
 * <p>When enabled, every {@code CoveragePlanner.plan()} call persists the deterministic decision —
 * change types, required scenario classes, capability list, covered/planned counts, recall, the
 * recurring rejection classes folded in, and (optionally) the full capability × class matrix — so
 * the team can later tune the {@code ChangeType → ScenarioClass} mapping and gap selection.
 *
 * <p>Artifacts contain only capability names, enum class names and counts — no secrets, no diff
 * content. The directory still defaults under {@code ./logs/} (gitignored) and must never be
 * committed.
 */
@Data
@ConfigurationProperties(prefix = "aiqa.coverage-plan.monitor")
public class CoveragePlanMonitorProperties {

    /** Master switch. When {@code false} (default) the monitor bean is absent — no overhead. */
    private boolean enabled = false;

    /** Root directory for plan artifacts. Default {@code ./logs/coverage-plans} (gitignored). */
    private String dir = "./logs/coverage-plans";

    /** Persist the full capability × class cell list per plan. When false, only counts/recall. */
    private boolean includeCells = true;

    /** Hard cap on cells written per plan (defence against pathological matrices). 0 = unlimited. */
    private int maxCells = 500;
}
