package nz.co.eroad.qaisystem.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates common error conditions into clean JSON 4xx responses.
 *
 * <h2>Why this matters for raw diffs</h2>
 * The {@code raw_diff} field is a plain string so it must be JSON-encoded by the
 * caller.  Characters that break inline JSON include:
 * <ul>
 *   <li>{@code "} (double quotes) — must be {@code \"}</li>
 *   <li>{@code \} (backslash)     — must be {@code \\}</li>
 *   <li>Literal newlines          — must be {@code \n}</li>
 * </ul>
 * Curly braces, unbalanced braces, and any other code structure are fine inside
 * a JSON string — the diff parser operates on lines, not on code semantics.
 *
 * <p>The safest way to send a diff is via a pre-encoded file:
 * <pre>
 *   # Encode the diff correctly then send
 *   python3 -c "
 *     import json, sys
 *     diff = open('my.diff').read()
 *     print(json.dumps({'title':'...','author':'...','repositoryName':'...','raw_diff': diff}))
 *   " > payload.json
 *   curl -X POST http://localhost:8080/api/pr/webhook \
 *        -H 'Content-Type: application/json' -d @payload.json
 * </pre>
 */
@Slf4j
@RestControllerAdvice
public class PRControllerAdvice {

    /**
     * Handles malformed JSON bodies — most commonly caused by unescaped {@code "}
     * or {@code \} characters inside the {@code raw_diff} string value.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBadJson(HttpMessageNotReadableException ex) {
        String root = rootMessage(ex);
        log.warn("[PRControllerAdvice] Malformed JSON request body: {}", root);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",  "BAD_REQUEST");
        body.put("error",   "Malformed JSON — could not parse request body");
        body.put("detail",  root);
        body.put("hint",    "If raw_diff contains double-quotes or backslashes, "
                + "they must be escaped as \\\" and \\\\ in JSON. "
                + "Easiest fix: encode with  python3 -c \"import json,sys; "
                + "print(json.dumps(sys.stdin.read()))\"  and embed the result.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@code @Valid} constraint violations on the request body.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());
        log.warn("[PRControllerAdvice] Validation failed: {}", errors);

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "status", "VALIDATION_FAILED",
                "errors", errors
        ));
    }

    /**
     * Handles business-rule violations thrown by PRService (missing title, author, etc.).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        log.warn("[PRControllerAdvice] Invalid PR payload: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "status", "INVALID_PAYLOAD",
                "error",  ex.getMessage()
        ));
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}

