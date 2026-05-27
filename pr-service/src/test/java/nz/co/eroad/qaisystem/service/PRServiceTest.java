package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.kafka.FeatureUpdatesProducer;
import nz.co.eroad.qaisystem.model.PullRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PRService unit tests")
class PRServiceTest {
    @Mock FeatureUpdatesProducer featureUpdatesProducer;
    @InjectMocks PRService prService;
    PullRequest valid;

    @BeforeEach void setUp() {
        valid = PullRequest.builder().title("feat: login").author("dev@example.com").repositoryName("svc").build();
    }

    @Test @DisplayName("assigns prId when missing")
    void assignsPrId() {
        assertThat(prService.processPullRequest(valid).getPrId()).startsWith("PR-");
        verify(featureUpdatesProducer).publishPullRequest(any());
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
        verifyNoInteractions(featureUpdatesProducer);
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

    @Test @DisplayName("createSamplePullRequest returns valid PR")
    void samplePrValid() {
        PullRequest s = prService.createSamplePullRequest();
        assertThat(s.getPrId()).isNotNull();
        assertThat(s.getDiffs()).isNotEmpty();
    }
}
