package nz.co.eroad.qaisystem.engine;

import nz.co.eroad.qaisystem.model.GitDiff;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GitDiffParser unit tests")
class GitDiffParserTest {
    GitDiffParser parser;

    @BeforeEach void setUp() { parser = new GitDiffParser(); }

    static final String MODIFIED = "diff --git a/src/main/java/A.java b/src/main/java/A.java\n"
        + "--- a/src/main/java/A.java\n"
        + "+++ b/src/main/java/A.java\n"
        + "@@ -1,2 +1,4 @@\n"
        + " context\n"
        + "-removed\n"
        + "+added line one\n"
        + "+added line two\n"
        + "+added line three\n";

    static final String NEW_FILE = "diff --git a/src/main/java/B.java b/src/main/java/B.java\n"
        + "new file mode 100644\n"
        + "--- /dev/null\n"
        + "+++ b/src/main/java/B.java\n"
        + "@@ -0,0 +1,2 @@\n"
        + "+public class B {}\n"
        + "+\n";

    static final String TEST_FILE = "diff --git a/src/test/java/ATest.java b/src/test/java/ATest.java\n"
        + "--- a/src/test/java/ATest.java\n"
        + "+++ b/src/test/java/ATest.java\n"
        + "@@ -1,1 +1,2 @@\n"
        + "+// test\n";

    @Test @DisplayName("parses modified file")
    void parse_modifiedFile() {
        List<GitDiff> diffs = parser.parse(MODIFIED);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getDiffType()).isEqualTo(GitDiff.DiffType.MODIFIED);
        assertThat(diffs.get(0).isTestFile()).isFalse();
        assertThat(diffs.get(0).getLinesAdded()).isEqualTo(3);
        assertThat(diffs.get(0).getLinesDeleted()).isEqualTo(1);
    }

    @Test @DisplayName("detects new file mode as ADDED")
    void parse_newFile() {
        List<GitDiff> diffs = parser.parse(NEW_FILE);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getDiffType()).isEqualTo(GitDiff.DiffType.ADDED);
        assertThat(diffs.get(0).getLinesAdded()).isEqualTo(2);
    }

    @Test @DisplayName("identifies test file by path")
    void parse_testFile() {
        assertThat(parser.parse(TEST_FILE).get(0).isTestFile()).isTrue();
    }

    @Test @DisplayName("returns empty list for null input")
    void parse_null_empty() { assertThat(parser.parse(null)).isEmpty(); }

    @Test @DisplayName("returns empty list for blank input")
    void parse_blank_empty() { assertThat(parser.parse("   ")).isEmpty(); }

    @Test @DisplayName("extracts java file extension")
    void parse_extension() {
        assertThat(parser.parse(MODIFIED).get(0).getFileExtension()).isEqualTo("java");
    }

    @Test @DisplayName("parses multiple files in one diff")
    void parse_multipleFiles() {
        assertThat(parser.parse(MODIFIED + NEW_FILE)).hasSize(2);
    }
}
