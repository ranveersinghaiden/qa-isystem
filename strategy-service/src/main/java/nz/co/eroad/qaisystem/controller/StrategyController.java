package nz.co.eroad.qaisystem.controller;

import nz.co.eroad.qaisystem.kafka.TestScriptsProducer;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.service.RepoContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Strategy-service endpoints.
 * POST /api/strategy/approve-bdd      — simulate BDD PR merge → triggers codegen
 * POST /api/strategy/refresh-context  — pull latest from the target test repo
 * GET  /api/strategy/status           — health / context check
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final TestScriptsProducer testScriptsProducer;
    private final RepoContextService  repoContextService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var api    = repoContextService.getContext("API");
        var ui     = repoContextService.getContext("UI");
        var mobile = repoContextService.getContext("MOBILE");
        return ResponseEntity.ok(Map.of(
                "service",   "strategy-service",
                "status",    "OPERATIONAL",
                "timestamp", LocalDateTime.now().toString(),
                "repoContext", Map.of(
                        "api",    contextSummary(api),
                        "ui",     contextSummary(ui),
                        "mobile", contextSummary(mobile))));
    }

    /**
     * Pulls the latest commits from the target test repo and refreshes the
     * in-memory context cache without restarting the service.
     * Useful after updating the target repo with new test patterns.
     */
    @PostMapping("/refresh-context")
    public ResponseEntity<Map<String, Object>> refreshContext() {
        log.info("[StrategyController] Manual context refresh requested");
        Map<String, Object> result = repoContextService.refresh();
        return ResponseEntity.ok(result);
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

    /**
     * Simulates a human approving and merging the BDD review PR.
     * In production this is triggered by the GitHub/GitLab merge webhook.
     * After approval, the BDD scenario is published to TestScriptsQueue
     * so codegen can start.
     */
    @PostMapping("/approve-bdd")
    public ResponseEntity<Map<String, Object>> approveBdd(@RequestBody BddScenario bdd) {
        log.info("[StrategyController] BDD approved for PR '{}' → publishing to TestScriptsQueue",
                bdd.getPrId());
        testScriptsProducer.publishBddScenario(bdd);
        return ResponseEntity.accepted().body(Map.of(
                "status",     "CODEGEN_TRIGGERED",
                "prId",       bdd.getPrId(),
                "scenarioId", bdd.getScenarioId(),
                "scenarios",  bdd.getScenarios() != null ? bdd.getScenarios().size() : 0,
                "message",    "BDD approved — code generation pipeline started"));
    }
}

