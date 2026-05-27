package nz.co.eroad.qaisystem.engine;

import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Analyses diff content and file paths to classify the type of change.
 */
@Slf4j
@Component
public class ChangeTypeDetector {

    // Keyword → ChangeType mappings
    private static final Map<Pattern, ChangeType> CONTENT_PATTERNS;

    static {
        CONTENT_PATTERNS = new LinkedHashMap<>();
        CONTENT_PATTERNS.put(
                Pattern.compile("(?i)(TODO|FIXME|bug|fix|patch|issue|defect)"),
                ChangeType.BUG_FIX);
        CONTENT_PATTERNS.put(
                Pattern.compile("(?i)(@Deprecated|rename|extract|move|refactor)"),
                ChangeType.REFACTORING);
        CONTENT_PATTERNS.put(
                Pattern.compile("(?i)(security|auth|crypt|token|password|secret)"),
                ChangeType.SECURITY_FIX);
        CONTENT_PATTERNS.put(
                Pattern.compile("(?i)(performance|cache|index|optimiz|async|parallel)"),
                ChangeType.PERFORMANCE_IMPROVEMENT);
        CONTENT_PATTERNS.put(
                Pattern.compile("(?i)(@RestController|@RequestMapping|@GetMapping"
                        + "|@PostMapping|@PutMapping|@DeleteMapping)"),
                ChangeType.API_CHANGE);
        CONTENT_PATTERNS.put(
                Pattern.compile("(?i)(migration|schema|ALTER TABLE|CREATE TABLE"
                        + "|DROP TABLE|liquibase|flyway)"),
                ChangeType.DATABASE_CHANGE);
    }

    private static final Map<Pattern, ChangeType> FILE_PATH_PATTERNS;

    static {
        FILE_PATH_PATTERNS = new LinkedHashMap<>();
        FILE_PATH_PATTERNS.put(
                Pattern.compile("(?i)(application\\.yml|application\\.properties"
                        + "|\\.env|config/)"),
                ChangeType.CONFIGURATION_CHANGE);
        FILE_PATH_PATTERNS.put(
                Pattern.compile("(?i)(pom\\.xml|build\\.gradle|\\.gradle|package\\.json)"),
                ChangeType.DEPENDENCY_UPDATE);
        FILE_PATH_PATTERNS.put(
                Pattern.compile("(?i)(migration|flyway|liquibase|sql)"),
                ChangeType.DATABASE_CHANGE);
    }

    /**
     * Returns a deduplicated list of detected change types across all diffs.
     */
    public List<ChangeType> detect(List<GitDiff> diffs) {
        Set<ChangeType> detected = new LinkedHashSet<>();

        for (GitDiff diff : diffs) {

            // ── 1. New file → NEW_FEATURE
            if (diff.getDiffType() == GitDiff.DiffType.ADDED
                    && !diff.isTestFile()) {
                detected.add(ChangeType.NEW_FEATURE);
            }

            // ── 2. Deleted file → possible REFACTORING
            if (diff.getDiffType() == GitDiff.DiffType.DELETED) {
                detected.add(ChangeType.REFACTORING);
            }

            // ── 3. File-path based classification
            FILE_PATH_PATTERNS.forEach((pattern, type) -> {
                if (pattern.matcher(diff.getFilePath()).find()) {
                    detected.add(type);
                }
            });

            // ── 4. Content-based classification (added/removed lines)
            String content = extractChangedContent(diff);
            CONTENT_PATTERNS.forEach((pattern, type) -> {
                if (pattern.matcher(content).find()) {
                    detected.add(type);
                }
            });

            // ── 5. Large deletions can be breaking changes
            if (diff.getLinesDeleted() > 50) {
                detected.add(ChangeType.BREAKING_CHANGE);
            }
        }

        // Default to NEW_FEATURE if nothing detected
        if (detected.isEmpty()) {
            detected.add(ChangeType.NEW_FEATURE);
        }

        log.debug("[ChangeTypeDetector] Detected types: {}", detected);
        return new ArrayList<>(detected);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String extractChangedContent(GitDiff diff) {
        StringBuilder sb = new StringBuilder();
        for (GitDiff.DiffHunk hunk : diff.getHunks()) {
            for (GitDiff.DiffLine line : hunk.getLines()) {
                if (line.getType() != GitDiff.DiffLine.LineType.CONTEXT) {
                    sb.append(line.getContent()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}