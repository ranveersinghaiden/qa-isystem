package nz.co.eroad.qaisystem.controller;

import nz.co.eroad.qaisystem.github.BddScenarioStore;
import nz.co.eroad.qaisystem.github.PrTracker;
import nz.co.eroad.qaisystem.kafka.TestScriptsProducer;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.PrType;
import nz.co.eroad.qaisystem.service.RepoContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Strategy-service endpoints.
 * POST /api/strategy/approve-bdd      — simulate BDD PR merge → triggers codegen (admin-key protected)
 * POST /api/strategy/refresh-context  — pull latest from the target test repo (admin-key protected)
 * GET  /api/strategy/pending-bdd      — list pending BDD scenarios (admin-key protected)
 * GET  /api/strategy/status           — health / context check (public)
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final TestScriptsProducer testScriptsProducer;
    private final RepoContextService  repoContextService;
    private final BddScenarioStore    bddScenarioStore;
    private final PrTracker           prTracker;

    /**
     * Optional admin API key protecting mutating/sensitive endpoints.
     * When blank (default for local dev), all requests are allowed.
     * In production: set AIQA_ADMIN_KEY to a strong random secret.
     */
    @Value("${aiqa.api.admin-key:}")
    private String adminKey;

    // ─── Public ───────────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var api    = repoContextService.getContext("API");
        var ui     = repoContextService.getContext("UI");
        var mobile = repoContextService.getContext("MOBILE");
        return ResponseEntity.ok(Map.of(
                "service",   "strategy-service",
                "status",    "OPERATIONAL",
                "timestamp", LocalDateTime.now().toString(),
                "pendingBddReviews", bddScenarioStore.size(),
                "repoContext", Map.of(
                        "api",    contextSummary(api),
                        "ui",     contextSummary(ui),
                        "mobile", contextSummary(mobile))));
    }

    // ─── Admin-key-protected ──────────────────────────────────────────────────

    /**
     * Pulls the latest commits from the target test repo and refreshes the
     * in-memory context cache without restarting the service.
     */
    @PostMapping("/refresh-context")
    public ResponseEntity<Map<String, Object>> refreshContext(HttpServletRequest request) {
        if (!isAuthorized(request)) return unauthorized();
        log.info("[StrategyController] Manual context refresh requested");
        Map<String, Object> result = repoContextService.refresh();
        return ResponseEntity.ok(result);
    }

    /**
     * Returns all currently tracked BDD scenario PRs waiting for human review.
     */
    @GetMapping("/pending-bdd")
    public ResponseEntity<List<Map<String, Object>>> pendingBdd(HttpServletRequest request) {
        if (!isAuthorized(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(List.of(Map.of("error", "X-Admin-Key required")));
        List<Map<String, Object>> pending = prTracker.findAll().stream()
                .filter(r -> r.getType() == PrType.BDD && r.getBddScenario() != null)
                .map(r -> Map.<String, Object>of(
                        "branch",      r.getBranchName(),
                        "prNumber",    r.getPrNumber(),
                        "bddScenario", r.getBddScenario()))
                .toList();
        log.info("[StrategyController] Returning {} pending BDD scenario(s)", pending.size());
        return ResponseEntity.ok(pending);
    }

    /**
     * Manual trigger — publishes a {@link BddScenario} directly to {@code TestScriptsQueue}.
     */
    @PostMapping("/approve-bdd")
    public ResponseEntity<Map<String, Object>> approveBdd(
            @RequestBody BddScenario bdd, HttpServletRequest request) {
        if (!isAuthorized(request)) return unauthorized();
        log.info("[StrategyController] BDD approved for PR '{}' → fanning out scenarios to TestScriptsQueue",
                bdd.getPrId());
        int published = testScriptsProducer.publishScenarioRequests(bdd);
        return ResponseEntity.accepted().body(Map.of(
                "status",     "CODEGEN_TRIGGERED",
                "prId",       bdd.getPrId(),
                "scenarioId", bdd.getScenarioId(),
                "scenarios",  published,
                "message",    "BDD approved — " + published + " scenario request(s) published for parallel code generation"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true when the request carries a valid admin key, or when no key is
     * configured (local dev / open mode). Logs a warning in open mode.
     */
    private boolean isAuthorized(HttpServletRequest request) {
        if (adminKey == null || adminKey.isBlank()) {
            log.warn("[StrategyController] AIQA_ADMIN_KEY not configured — admin endpoints are " +
                     "open to all callers. Set aiqa.api.admin-key (or AIQA_ADMIN_KEY env var) " +
                     "in production.");
            return true;
        }
        String provided = request.getHeader("X-Admin-Key");
        if (adminKey.equals(provided)) return true;
        log.warn("[StrategyController] Admin request rejected — invalid or missing X-Admin-Key");
        return false;
    }

    private <T> ResponseEntity<T> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private Map<String, Object> contextSummary(nz.co.eroad.qaisystem.execution.RepoContext ctx) {
        if (!ctx.isContextAvailable()) return Map.of("available", false);
        return Map.of(
                "available",  true,
                "testFiles",  ctx.getExistingTestFileNames() != null
                        ? ctx.getExistingTestFileNames().size() : 0,
                "basePackage", ctx.getBasePackage() != null ? ctx.getBasePackage() : "",
                "baseClass",  ctx.getBaseTestClass() != null ? ctx.getBaseTestClass() : "none",
                "naming",     ctx.getTestNamingConvention() != null
                        ? ctx.getTestNamingConvention() : "*Test.java");
    }
}
