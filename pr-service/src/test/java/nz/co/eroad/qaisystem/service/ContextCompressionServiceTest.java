package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.agent.AiClient;
import nz.co.eroad.qaisystem.model.PullRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ContextCompressionService} using real inner-class test doubles.
 * Zero Mockito.
 */
@DisplayName("ContextCompressionService unit tests")
class ContextCompressionServiceTest {

    // ─── Test doubles ──────────────────────────────────────────────────────────

    /** Stub AiClient that returns a fixed response or throws, based on configuration. */
    static class StubAiClient implements AiClient {
        private final boolean available;
        private final String  fixedResponse;   // null means "simulate null response"
        private final boolean shouldThrow;

        StubAiClient(boolean available, String fixedResponse, boolean shouldThrow) {
            this.available     = available;
            this.fixedResponse = fixedResponse;
            this.shouldThrow   = shouldThrow;
        }

        @Override
        public boolean isAvailable() { return available; }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            if (shouldThrow) throw new RuntimeException("Simulated AI failure");
            return fixedResponse;
        }
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    private PullRequest validPr;

    @BeforeEach
    void setUp() {
        validPr = PullRequest.builder()
                .prId("PR-TEST01")
                .title("feat: add payment gateway")
                .description("This PR adds a Stripe payment gateway integration.\n"
                        + "Please review the implementation and check the edge cases.\n"
                        + "See screenshots above.")
                .author("dev@example.com")
                .sourceBranch("feature/payment-gateway")
                .targetBranch("main")
                .repositoryName("payments-service")
                .labels(List.of("payments", "backend"))
                .jiraIds(List.of("PAY-123"))
                .products(List.of("payments"))
                .build();
    }

    // ─── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isEnabled() returns false when aiClient is null")
    void nullClient_isDisabled() {
        var service = new ContextCompressionService(null);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("compress() returns original PR unchanged when client is null")
    void nullClient_compressReturnsOriginal() {
        var service = new ContextCompressionService(null);
        PullRequest result = service.compress(validPr);
        assertThat(result).isSameAs(validPr);
        assertThat(result.getContextSummary()).isNull();
    }

    @Test
    @DisplayName("isEnabled() returns false when client is not available")
    void unavailableClient_isDisabled() {
        var client  = new StubAiClient(false, "some summary", false);
        var service = new ContextCompressionService(client);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("compress() returns original PR unchanged when client is not available")
    void unavailableClient_compressReturnsOriginal() {
        var client  = new StubAiClient(false, "some summary", false);
        var service = new ContextCompressionService(client);
        PullRequest result = service.compress(validPr);
        assertThat(result).isSameAs(validPr);
        assertThat(result.getContextSummary()).isNull();
    }

    @Test
    @DisplayName("compress() sets contextSummary when client returns a valid summary")
    void enabledClient_setsSummary() {
        var summary = "Payment gateway integration added. Risk: payment edge cases, Stripe API errors.";
        var client  = new StubAiClient(true, summary, false);
        var service = new ContextCompressionService(client);

        PullRequest result = service.compress(validPr);

        assertThat(result.getContextSummary()).isEqualTo(summary);
        // Original description must be preserved
        assertThat(result.getDescription()).isEqualTo(validPr.getDescription());
    }

    @Test
    @DisplayName("compress() preserves all original PR fields when setting contextSummary")
    void enabledClient_preservesOriginalFields() {
        var summary = "Compressed summary text.";
        var client  = new StubAiClient(true, summary, false);
        var service = new ContextCompressionService(client);

        PullRequest result = service.compress(validPr);

        assertThat(result.getPrId()).isEqualTo(validPr.getPrId());
        assertThat(result.getTitle()).isEqualTo(validPr.getTitle());
        assertThat(result.getDescription()).isEqualTo(validPr.getDescription());
        assertThat(result.getAuthor()).isEqualTo(validPr.getAuthor());
        assertThat(result.getSourceBranch()).isEqualTo(validPr.getSourceBranch());
        assertThat(result.getTargetBranch()).isEqualTo(validPr.getTargetBranch());
        assertThat(result.getRepositoryName()).isEqualTo(validPr.getRepositoryName());
        assertThat(result.getLabels()).isEqualTo(validPr.getLabels());
        assertThat(result.getJiraIds()).isEqualTo(validPr.getJiraIds());
        assertThat(result.getProducts()).isEqualTo(validPr.getProducts());
        assertThat(result.getContextSummary()).isEqualTo(summary);
    }

    @Test
    @DisplayName("compress() returns original PR unchanged when client returns null")
    void clientReturnsNull_returnsOriginal() {
        var client  = new StubAiClient(true, null, false);
        var service = new ContextCompressionService(client);

        PullRequest result = service.compress(validPr);

        assertThat(result).isSameAs(validPr);
        assertThat(result.getContextSummary()).isNull();
    }

    @Test
    @DisplayName("compress() returns original PR unchanged when client returns blank string")
    void clientReturnsBlank_returnsOriginal() {
        var client  = new StubAiClient(true, "   ", false);
        var service = new ContextCompressionService(client);

        PullRequest result = service.compress(validPr);

        assertThat(result).isSameAs(validPr);
        assertThat(result.getContextSummary()).isNull();
    }

    @Test
    @DisplayName("compress() swallows exception and returns original PR when client throws")
    void clientThrows_returnsOriginalAndSwallows() {
        var client  = new StubAiClient(true, null, true);
        var service = new ContextCompressionService(client);

        // Must not propagate — exception is swallowed at the boundary
        PullRequest result = service.compress(validPr);

        assertThat(result).isSameAs(validPr);
        assertThat(result.getContextSummary()).isNull();
    }

    @Test
    @DisplayName("compress() strips leading/trailing whitespace from AI response")
    void clientReturnsPaddedSummary_getsStripped() {
        var raw     = "  Payment gateway changes.  ";
        var client  = new StubAiClient(true, raw, false);
        var service = new ContextCompressionService(client);

        PullRequest result = service.compress(validPr);

        assertThat(result.getContextSummary()).isEqualTo("Payment gateway changes.");
    }
}

