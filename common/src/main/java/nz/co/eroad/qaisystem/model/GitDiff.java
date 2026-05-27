package nz.co.eroad.qaisystem.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitDiff {

    private String filePath;
    private String oldFilePath;
    private DiffType diffType;
    private List<DiffHunk> hunks;
    private int linesAdded;
    private int linesDeleted;
    private String fileExtension;
    private boolean isTestFile;

    public enum DiffType {
        ADDED, MODIFIED, DELETED, RENAMED, COPIED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffHunk {
        private int oldStart;
        private int oldCount;
        private int newStart;
        private int newCount;
        private List<DiffLine> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffLine {
        private LineType type;
        private int lineNumber;
        private String content;

        public enum LineType {
            ADDED, REMOVED, CONTEXT
        }
    }
}
