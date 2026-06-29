package nz.co.eroad.qaisystem.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.config.CoveragePlanMonitorProperties;
import nz.co.eroad.qaisystem.model.ScenarioClass;
import nz.co.eroad.qaisystem.model.ScenarioMatrix;
import nz.co.eroad.qaisystem.model.ScenarioMatrix.Cell;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Persists, per {@code CoveragePlanner.plan()} call, the deterministic gap-matrix decision so the
 * team can later optimise the {@code ChangeType → ScenarioClass} mapping and PLANNED-cell selection.
 * Mirrors {@link nz.co.eroad.qaisystem.trace.ContextTraceRecorder}: a single {@code plan-{ts}.json}
 * per plan plus one appended summary line in {@code coverage-plan-index.jsonl} at the monitor root.
 *
 * <p>This bean exists <strong>only</strong> when {@code aiqa.coverage-plan.monitor.enabled=true};
 * when the flag is unset there is no monitor and the planner is unaffected.
 *
 * <p><strong>Reliability contract:</strong> {@link #record} is best-effort — all I/O is wrapped,
 * logged at WARN and <em>never</em> throws. Monitoring must never break the strategy pipeline.
 *
 * <p>Artifacts hold only capability names, enum class names and counts (no diffs, no secrets), but
 * the prId segment can be webhook-derived so it is sanitised against path injection and the
 * resolved directory is verified to stay under the configured root.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aiqa.coverage-plan.monitor.enabled", havingValue = "true")
@EnableConfigurationProperties(CoveragePlanMonitorProperties.class)
public class CoveragePlanMonitor {

    private static final String LOG_PREFIX = "[CoveragePlanMonitor]";
    private static final String INDEX_FILE = "coverage-plan-index.jsonl";

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    private static final FileAttribute<?> FILE_PERMS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final FileAttribute<?> DIR_PERMS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    private final CoveragePlanMonitorProperties props;
    private final ObjectMapper                  objectMapper;
    private final Object                        indexLock = new Object();

    /**
     * Persists one coverage-plan decision. Best-effort — returns silently (and never throws) on any
     * I/O or serialisation failure so the planner is unaffected.
     */
    public void record(String prId, List<String> changeTypes, List<String> capabilities,
                       List<ScenarioClass> requiredClasses, ScenarioMatrix matrix) {
        if (matrix == null) {
            return;
        }
        try {
            Instant now    = Instant.now();
            String  planId = UUID.randomUUID().toString();
            Path    root   = Path.of(props.getDir()).toAbsolutePath().normalize();
            Path    dir    = root.resolve(DAY_FMT.format(now)).resolve(sanitize(prId)).normalize();

            // SECURITY: defence-in-depth against path escape even after sanitisation.
            if (!dir.startsWith(root)) {
                log.warn("{} Resolved dir '{}' escapes root '{}' — skipping plan record", LOG_PREFIX, dir, root);
                return;
            }
            createDirs(dir);

            List<Cell> cells = props.isIncludeCells() ? capCells(matrix.cells()) : List.of();
            PlanRecord rec = new PlanRecord(
                    planId, now.toString(), nz(prId), nz(changeTypes), nz(capabilities),
                    requiredClasses == null ? List.of() : requiredClasses.stream().map(Enum::name).toList(),
                    matrix.coveredCount(), matrix.plannedCount(), matrix.gaps().size(),
                    round2(matrix.recall()), cells.size(), cells);

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rec);
            writeSecure(dir.resolve("plan-" + TS_FMT.format(now) + ".json"), json);
            appendIndexLine(root, rec);

            log.info("{} PR '{}' plan recorded: {} caps × {} classes → {} gaps recall={} planId={}",
                    LOG_PREFIX, prId, capabilities == null ? 0 : capabilities.size(),
                    requiredClasses == null ? 0 : requiredClasses.size(), matrix.gaps().size(),
                    round2(matrix.recall()), planId);
        } catch (Exception e) {
            log.warn("{} record() failed for prId={}: {} — monitoring skipped this plan", LOG_PREFIX, prId, e.getMessage());
        }
    }

    // ─── Internals ──────────────────────────────────────────────────────────────

    private List<Cell> capCells(List<Cell> cells) {
        int cap = props.getMaxCells();
        return (cap > 0 && cells.size() > cap) ? cells.subList(0, cap) : cells;
    }

    private void appendIndexLine(Path root, PlanRecord rec) {
        try {
            Path index = root.resolve(INDEX_FILE);
            PlanRecord summary = new PlanRecord(rec.planId(), rec.timestamp(), rec.prId(),
                    rec.changeTypes(), rec.capabilities(), rec.requiredClasses(), rec.covered(),
                    rec.planned(), rec.gaps(), rec.recall(), rec.cellCount(), List.of());
            String line = objectMapper.writeValueAsString(summary) + System.lineSeparator();
            synchronized (indexLock) {
                if (Files.notExists(index)) {
                    try {
                        Files.createFile(index, FILE_PERMS);
                    } catch (UnsupportedOperationException u) {
                        Files.createFile(index);
                    } catch (FileAlreadyExistsException ignored) {
                        // concurrent create — fine
                    }
                }
                Files.writeString(index, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("{} Failed to append index line: {}", LOG_PREFIX, e.getMessage());
        }
    }

    private void createDirs(Path dir) throws java.io.IOException {
        try {
            Files.createDirectories(dir, DIR_PERMS);
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir);
        }
    }

    private void writeSecure(Path file, String content) throws java.io.IOException {
        try {
            Files.createFile(file, FILE_PERMS);
        } catch (UnsupportedOperationException e) {
            if (Files.notExists(file)) Files.createFile(file);
        } catch (FileAlreadyExistsException ignored) {
            // unique timestamp — reuse/truncate
        }
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** SECURITY: collapse a (possibly webhook-derived) prId into one safe path component. */
    static String sanitize(String segment) {
        if (segment == null || segment.isBlank()) {
            return "unknown";
        }
        String cleaned = segment.replaceAll("[^A-Za-z0-9._-]", "_");
        int i = 0;
        while (i < cleaned.length() && cleaned.charAt(i) == '.') i++;
        cleaned = cleaned.substring(i);
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    /** Immutable per-plan metadata, serialised to {@code plan-*.json} and (cell-free) to the index. */
    public record PlanRecord(
            String              planId,
            String              timestamp,
            String              prId,
            List<String>        changeTypes,
            List<String>        capabilities,
            List<String>        requiredClasses,
            long                covered,
            long                planned,
            int                 gaps,
            double              recall,
            int                 cellCount,
            List<Cell>          cells
    ) {}
}
