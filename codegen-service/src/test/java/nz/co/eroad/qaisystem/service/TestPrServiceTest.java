package nz.co.eroad.qaisystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.config.TargetRepoProperties;
import nz.co.eroad.qaisystem.github.GitHubService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zero-mock tests for {@link TestPrService#createAggregateTestPr} (gap 3 — the one-shot
 * gather step opens ONE aggregate test PR). The Kafka per-scenario path is untouched.
 */
class TestPrServiceTest {

    /** Fake GitHub client recording branch/file/PR calls and returning a canned PR result. */
    static final class FakeGitHubService extends GitHubService {
        boolean configured = true;
        int branchCalls;
        int prCalls;
        final List<String> committedPaths = new ArrayList<>();
        private final GitHubPrResult prResult;

        FakeGitHubService(GitHubPrResult prResult) {
            // url defaults to "" → owner null → no token resolution / no interactive prompt.
            super(new TargetRepoProperties(), new ObjectMapper());
            this.prResult = prResult;
        }

        @Override public boolean isConfigured() { return configured; }

        @Override
        public boolean createBranch(String newBranchName, String baseBranch) {
            branchCalls++;
            return true;
        }

        @Override
        public boolean createFile(String branch, String filePath, String content, String message) {
            committedPaths.add(filePath);
            return true;
        }

        @Override
        public GitHubPrResult createPullRequest(String title, String body, String head, String base) {
            prCalls++;
            return prResult;
        }
    }

    private static TestPrService serviceWith(FakeGitHubService gitHub) {
        // prTracker is intentionally null: createAggregateTestPr does NOT use it (one-shot routing
        // is driven by pr_history gate state, not PrTracker).
        return new TestPrService(gitHub, null, new TargetRepoProperties());
    }

    @Test
    @DisplayName("aggregate PR: blank-content entries skipped; one branch, one PR, prNumber surfaced")
    void aggregate_skipsBlankContent_opensOnePr() {
        FakeGitHubService gitHub = new FakeGitHubService(
                new GitHubService.GitHubPrResult(42, "http://pr/42", "qa/tests/PR-1-abc123"));
        TestPrService service = serviceWith(gitHub);

        List<TestPrService.AggregateTestFile> files = List.of(
                new TestPrService.AggregateTestFile("a/AApiTest.java", "class A {}"),
                new TestPrService.AggregateTestFile("a/BApiTest.java", "class B {}"),
                new TestPrService.AggregateTestFile("a/BlankTest.java", "   "));

        GitHubService.GitHubPrResult pr = service.createAggregateTestPr("PR-1", "Title One", files);

        assertThat(pr.prNumber()).isEqualTo(42);
        assertThat(gitHub.branchCalls).isEqualTo(1);
        assertThat(gitHub.prCalls).isEqualTo(1);
        // blank-content entry skipped → only two files committed
        assertThat(gitHub.committedPaths).containsExactly("a/AApiTest.java", "a/BApiTest.java");
    }

    @Test
    @DisplayName("aggregate PR: duplicate file paths are de-duplicated (first wins)")
    void aggregate_dedupesDuplicatePaths() {
        FakeGitHubService gitHub = new FakeGitHubService(
                new GitHubService.GitHubPrResult(7, "http://pr/7", "qa/tests/PR-1-def456"));
        TestPrService service = serviceWith(gitHub);

        List<TestPrService.AggregateTestFile> files = List.of(
                new TestPrService.AggregateTestFile("a/DupTest.java", "class First {}"),
                new TestPrService.AggregateTestFile("a/DupTest.java", "class Second {}"),
                new TestPrService.AggregateTestFile("a/OtherTest.java", "class Other {}"));

        service.createAggregateTestPr("PR-1", null, files);

        assertThat(gitHub.committedPaths).containsExactly("a/DupTest.java", "a/OtherTest.java");
    }

    @Test
    @DisplayName("aggregate PR: throws when GitHub is not configured (consistent with the guard)")
    void aggregate_notConfigured_throws() {
        FakeGitHubService gitHub = new FakeGitHubService(
                new GitHubService.GitHubPrResult(1, "u", "b"));
        gitHub.configured = false;
        TestPrService service = serviceWith(gitHub);

        assertThatThrownBy(() -> service.createAggregateTestPr("PR-1", "T",
                List.of(new TestPrService.AggregateTestFile("a/AApiTest.java", "class A {}"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(gitHub.branchCalls).isZero();
        assertThat(gitHub.prCalls).isZero();
    }
}
