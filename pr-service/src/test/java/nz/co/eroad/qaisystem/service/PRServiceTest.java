package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.kafka.FeatureUpdatesProducer;
import nz.co.eroad.qaisystem.model.PullRequest;
import nz.co.eroad.qaisystem.parser.GitDiffParser;
import org.junit.jupiter.api.*;
import org.springframework.kafka.support.SendResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;

/**
 * PRService tests using real test-double classes (no Mockito).
 */
@DisplayName("PRService unit tests")
class PRServiceTest {

    /** Captures published pull requests without calling Kafka. */
    static class CapturingProducer extends FeatureUpdatesProducer {
        final List<PullRequest> published = new ArrayList<>();
        CapturingProducer() { super(null, null); }
        @Override
        public CompletableFuture<SendResult<String, String>> publishPullRequest(PullRequest pr) {
            published.add(pr);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CapturingProducer producer;
    private PRService          prService;
    private PullRequest        valid;

    @BeforeEach
    void setUp() {
        producer  = new CapturingProducer();
        prService = new PRService(producer, new GitDiffParser(), new PrContextExtractor());
        valid     = PullRequest.builder().title("feat: login").author("dev@example.com").repositoryName("svc").build();
    }

    @Test @DisplayName("assigns prId when missing")
    void assignsPrId() {
        assertThat(prService.processPullRequest(valid).getPrId()).startsWith("PR-");
        assertThat(producer.published).hasSize(1);
    }

    @Test @DisplayName("preserves existing prId")
    void preservesPrId() {
        valid.setPrId("PR-X");
        assertThat(prService.processPullRequest(valid).getPrId()).isEqualTo("PR-X");
    }

    @Test @DisplayName("defaults targetBranch to main")
    void defaultsTargetBranch() {
        assertThat(prService.processPullRequest(valid).getTargetBranch()).isEqualTo("main");
    }

    @Test @DisplayName("defaults status to OPEN")
    void defaultsStatusOpen() {
        assertThat(prService.processPullRequest(valid).getStatus()).isEqualTo(PullRequest.PrStatus.OPEN);
    }

    @Test @DisplayName("throws on blank title")
    void throwsBlankTitle() {
        valid.setTitle("  ");
        assertThatThrownBy(() -> prService.processPullRequest(valid))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThat(producer.published).isEmpty();
    }

    @Test @DisplayName("throws on missing author")
    void throwsMissingAuthor() {
        valid.setAuthor(null);
        assertThatThrownBy(() -> prService.processPullRequest(valid))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("author");
    }

    @Test @DisplayName("throws on missing repo name")
    void throwsMissingRepo() {
        valid.setRepositoryName(null);
        assertThatThrownBy(() -> prService.processPullRequest(valid))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Repository");
    }

    @Test @DisplayName("parses raw_diff into structured diffs before publishing")
    void parsesRawDiffContent() {
        String rawDiff =
            "diff --git a/src/main/java/com/example/Foo.java b/src/main/java/com/example/Foo.java\n"
          + "--- a/src/main/java/com/example/Foo.java\n"
          + "+++ b/src/main/java/com/example/Foo.java\n"
          + "@@ -1,1 +1,2 @@\n"
          + "+// added\n";
        valid.setRawDiffContent(rawDiff);
        PullRequest result = prService.processPullRequest(valid);
        assertThat(result.getDiffs()).hasSize(1);
        assertThat(result.getDiffs().get(0).getLinesAdded()).isEqualTo(1);
        assertThat(producer.published).hasSize(1);
        assertThat(producer.published.get(0).getDiffs()).hasSize(1);
    }

    @Test @DisplayName("createSamplePullRequest builds valid PR with parseable diff")
    void samplePrValid() {
        PullRequest s = prService.createSamplePullRequest();
        assertThat(s.getPrId()).isNotNull();
        assertThat(s.getRawDiffContent()).isNotBlank();
        PullRequest processed = prService.processPullRequest(s);
        assertThat(processed.getDiffs()).isNotEmpty();
    }
}
