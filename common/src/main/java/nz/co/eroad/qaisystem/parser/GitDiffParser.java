package nz.co.eroad.qaisystem.parser;

import nz.co.eroad.qaisystem.model.GitDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a raw unified-diff string (output of {@code git diff}) into a structured
 * list of {@link GitDiff} objects.
 *
 * <p>Placed in {@code common} so both {@code pr-service} (which parses the
 * caller-supplied {@code raw_diff} payload before publishing to Kafka) and
 * {@code impact-service} (which re-parses when only raw content is available) can
 * share the same implementation without duplication.
 */
@Slf4j
@Component
public class GitDiffParser {

    // Matches:  diff --git a/foo/Bar.java b/foo/Bar.java
    private static final Pattern FILE_HEADER =
            Pattern.compile("^diff --git a/(.*) b/(.*)$");

    // Matches:  @@ -3,7 +3,8 @@
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private static final List<String> TEST_INDICATORS =
            List.of("Test", "Spec", "IT", "test/", "spec/");

    public List<GitDiff> parse(String rawDiff) {
        List<GitDiff> diffs = new ArrayList<>();
        if (rawDiff == null || rawDiff.isBlank()) {
            return diffs;
        }

        String[] lines  = rawDiff.split("\n");
        GitDiff current = null;
        GitDiff.DiffHunk currentHunk = null;
        int lineCounter = 0;

        for (String line : lines) {
            Matcher fileMatcher = FILE_HEADER.matcher(line);
            if (fileMatcher.matches()) {
                // Flush previous diff
                if (current != null) {
                    diffs.add(finalise(current));
                }
                String filePath = fileMatcher.group(2);
                current = GitDiff.builder()
                        .filePath(filePath)
                        .oldFilePath(fileMatcher.group(1))
                        .diffType(GitDiff.DiffType.MODIFIED)
                        .fileExtension(extractExtension(filePath))
                        .isTestFile(isTestFile(filePath))
                        .hunks(new ArrayList<>())
                        .linesAdded(0)
                        .linesDeleted(0)
                        .build();
                currentHunk = null;
                lineCounter = 0;
                continue;
            }

            if (current == null) continue;

            // Detect file type markers
            if (line.startsWith("new file mode")) {
                current.setDiffType(GitDiff.DiffType.ADDED);
            } else if (line.startsWith("deleted file mode")) {
                current.setDiffType(GitDiff.DiffType.DELETED);
            } else if (line.startsWith("rename")) {
                current.setDiffType(GitDiff.DiffType.RENAMED);
            }

            // Hunk header
            Matcher hunkMatcher = HUNK_HEADER.matcher(line);
            if (hunkMatcher.find()) {
                currentHunk = GitDiff.DiffHunk.builder()
                        .oldStart(Integer.parseInt(hunkMatcher.group(1)))
                        .oldCount(hunkMatcher.group(2) != null
                                ? Integer.parseInt(hunkMatcher.group(2)) : 1)
                        .newStart(Integer.parseInt(hunkMatcher.group(3)))
                        .newCount(hunkMatcher.group(4) != null
                                ? Integer.parseInt(hunkMatcher.group(4)) : 1)
                        .lines(new ArrayList<>())
                        .build();
                current.getHunks().add(currentHunk);
                lineCounter = currentHunk.getNewStart();
                continue;
            }

            // Diff lines
            if (currentHunk != null) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    currentHunk.getLines().add(GitDiff.DiffLine.builder()
                            .type(GitDiff.DiffLine.LineType.ADDED)
                            .lineNumber(lineCounter++)
                            .content(line.substring(1))
                            .build());
                    current.setLinesAdded(current.getLinesAdded() + 1);

                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    currentHunk.getLines().add(GitDiff.DiffLine.builder()
                            .type(GitDiff.DiffLine.LineType.REMOVED)
                            .lineNumber(lineCounter)
                            .content(line.substring(1))
                            .build());
                    current.setLinesDeleted(current.getLinesDeleted() + 1);

                } else if (!line.startsWith("---") && !line.startsWith("+++")) {
                    currentHunk.getLines().add(GitDiff.DiffLine.builder()
                            .type(GitDiff.DiffLine.LineType.CONTEXT)
                            .lineNumber(lineCounter++)
                            .content(line.startsWith(" ") ? line.substring(1) : line)
                            .build());
                }
            }
        }

        if (current != null) {
            diffs.add(finalise(current));
        }

        log.debug("[GitDiffParser] Parsed {} file diffs", diffs.size());
        return diffs;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private GitDiff finalise(GitDiff diff) {
        if (diff.getHunks() == null) diff.setHunks(new ArrayList<>());
        return diff;
    }

    private String extractExtension(String filePath) {
        int dot = filePath.lastIndexOf('.');
        return (dot >= 0) ? filePath.substring(dot + 1).toLowerCase() : "";
    }

    private boolean isTestFile(String filePath) {
        return TEST_INDICATORS.stream()
                .anyMatch(ind -> filePath.contains(ind));
    }
}

