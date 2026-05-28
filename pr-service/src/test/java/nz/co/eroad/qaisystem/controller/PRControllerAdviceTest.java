package nz.co.eroad.qaisystem.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PRControllerAdvice — directly exercising the handler methods
 * without a web layer so they are fast and framework-independent.
 *
 * The key scenarios covered:
 *
 * 1.  Malformed JSON (unescaped {@code "} or {@code \} in raw_diff) →
 *     400 BAD_REQUEST with a "hint" field explaining how to fix the encoding.
 *
 * 2.  Business-rule violation from PRService (missing title / author / repo) →
 *     422 UNPROCESSABLE_ENTITY with the original error message.
 *
 * Note: braces, angle-brackets, and all other characters EXCEPT {@code "} and
 * {@code \} are perfectly safe inside a JSON string and will never cause a parse
 * error, so no special handling is needed for unbalanced or unclosed braces in
 * diff content.
 */
@DisplayName("PRControllerAdvice unit tests")
class PRControllerAdviceTest {

    private final PRControllerAdvice advice = new PRControllerAdvice();

    private static HttpMessageNotReadableException badJson(String msg) {
        return new HttpMessageNotReadableException(msg,
                new MockHttpInputMessage("{}".getBytes(StandardCharsets.UTF_8)));
    }

    // ── Malformed JSON ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Malformed JSON returns 400 BAD_REQUEST")
    void malformedJson_returns400() {
        ResponseEntity<Map<String, Object>> response = advice.handleBadJson(badJson(
                "JSON parse error: Unexpected character ('o'): was expecting comma"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("status");
        assertThat(response.getBody()).containsKey("hint");
        assertThat(response.getBody().get("status")).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("Malformed JSON hint mentions double-quote escaping")
    void malformedJson_hintMentionsEscaping() {
        ResponseEntity<Map<String, Object>> response = advice.handleBadJson(badJson("bad json"));

        String hint = (String) response.getBody().get("hint");
        assertThat(hint).contains("double-quotes");
        assertThat(hint).contains("backslashes");
    }

    // ── Business-rule violations ──────────────────────────────────────────────

    @Test
    @DisplayName("IllegalArgumentException returns 422 INVALID_PAYLOAD")
    void illegalArgument_returns422() {
        IllegalArgumentException ex =
                new IllegalArgumentException("Invalid PullRequest: PR title is required");

        ResponseEntity<Map<String, Object>> response = advice.handleIllegalArg(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("status")).isEqualTo("INVALID_PAYLOAD");
        assertThat(response.getBody().get("error").toString()).contains("title");
    }

    @Test
    @DisplayName("IllegalArgumentException for missing author is reported correctly")
    void illegalArgument_missingAuthor() {
        IllegalArgumentException ex =
                new IllegalArgumentException("Invalid PullRequest: PR author is required");

        ResponseEntity<Map<String, Object>> response = advice.handleIllegalArg(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("error").toString()).contains("author");
    }

    // ── Sanity: curly braces are safe in JSON strings ─────────────────────────

    @Test
    @DisplayName("Advice does NOT treat unbalanced braces as an error — only JSON encoding matters")
    void unclosedBrace_notAnError() {
        // A diff line like "-  if (x) {" has an opening brace with no closing brace.
        // This is fine in a JSON string — no special handling needed.
        String diffWithUnclosedBrace =
                "diff --git a/Foo.java b/Foo.java\n"
              + "@@ -1,1 +1,2 @@\n"
              + "+  if (condition) {\n";   // unclosed brace — totally fine in JSON

        // The advice doesn't handle this case because it is NOT an error.
        // GitDiffParser will happily parse it as two added lines.
        nz.co.eroad.qaisystem.parser.GitDiffParser parser =
                new nz.co.eroad.qaisystem.parser.GitDiffParser();
        assertThat(parser.parse(diffWithUnclosedBrace)).hasSize(1);
        assertThat(parser.parse(diffWithUnclosedBrace).get(0).getLinesAdded()).isEqualTo(1);
    }
}

