package nz.co.eroad.qaisystem.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Tracks AI call costs via Micrometer counters and exposes a cost report endpoint.
 * Publishes a periodic log summary every {@code aiqa.monitor.report-interval-ms} milliseconds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCostMonitor {

    private static final String METRIC_TOTAL      = "aiqa.ai.calls.total";
    private static final String METRIC_SKIPPED    = "aiqa.ai.calls.skipped";
    private static final String METRIC_CACHE_HIT  = "aiqa.ai.calls.cache_hit";
    private static final String METRIC_EXECUTED   = "aiqa.ai.calls.executed";
    private static final String METRIC_FAILED     = "aiqa.ai.calls.failed";

    private final MeterRegistry meterRegistry;

    private Counter totalCounter;
    private Counter skippedCounter;
    private Counter cacheHitCounter;
    private Counter executedCounter;
    private Counter failedCounter;

    /** Registers all counters with the MeterRegistry. Called by Spring after construction. */
    @PostConstruct
    public void initMetrics() {
        totalCounter    = Counter.builder(METRIC_TOTAL)   .description("Total AI strategy requests").register(meterRegistry);
        skippedCounter  = Counter.builder(METRIC_SKIPPED) .description("Requests skipped by gate").register(meterRegistry);
        cacheHitCounter = Counter.builder(METRIC_CACHE_HIT).description("Cache hits avoiding AI").register(meterRegistry);
        executedCounter = Counter.builder(METRIC_EXECUTED).description("Actual AI calls executed").register(meterRegistry);
        failedCounter   = Counter.builder(METRIC_FAILED)  .description("AI calls that failed").register(meterRegistry);
        log.info("[AiCostMonitor] Metrics registered");
    }

    /** Increments the total-requests counter. */
    public void recordRequest()    { totalCounter.increment(); }

    /** Increments the gate-skipped counter. */
    public void recordGateSkip()   { skippedCounter.increment(); }

    /** Increments the cache-hit counter. */
    public void recordCacheHit()   { cacheHitCounter.increment(); }

    /** Increments the AI-executed counter. */
    public void recordAiExecuted() { executedCounter.increment(); }

    /** Increments the AI-failed counter. */
    public void recordAiFailure()  { failedCounter.increment(); }

    /**
     * Logs a periodic cost summary at the configured interval.
     */
    @Scheduled(fixedRateString = "${aiqa.monitor.report-interval-ms:300000}")
    public void logReport() {
        var report = generateReport();
        log.info("[AiCostMonitor] Cost report — total={} skipped={} cacheHits={} aiExecuted={} " +
                        "aiFailed={} skipRate={:.1f}% cacheHitRate={:.1f}% aiCallRate={:.1f}% estimatedSaved={:.1f}%",
                report.totalRequests(), report.skippedByGate(), report.cacheHits(),
                report.aiExecuted(), report.aiFailed(),
                report.skipRatePct(), report.cacheHitRatePct(),
                report.aiCallRatePct(), report.estimatedSavedPct());
    }

    /**
     * Builds and returns a snapshot of the current cost metrics.
     */
    public CostReport generateReport() {
        long total    = (long) totalCounter.count();
        long skipped  = (long) skippedCounter.count();
        long hits     = (long) cacheHitCounter.count();
        long executed = (long) executedCounter.count();
        long failed   = (long) failedCounter.count();

        double skipRate    = total > 0 ? (skipped  * 100.0 / total) : 0.0;
        double cacheHitRate= total > 0 ? (hits     * 100.0 / total) : 0.0;
        double aiCallRate  = total > 0 ? (executed * 100.0 / total) : 0.0;
        double savedPct    = total > 0 ? ((skipped + hits)  * 100.0 / total) : 0.0;

        return new CostReport(total, skipped, hits, executed, failed,
                LocalDateTime.now(), skipRate, cacheHitRate, aiCallRate, savedPct);
    }

    /**
     * Immutable snapshot of AI cost metrics at a point in time.
     *
     * @param totalRequests    total strategy requests received
     * @param skippedByGate    requests skipped by the {@link nz.co.eroad.qaisystem.gate.AiCallGate}
     * @param cacheHits        AI calls avoided via the prompt-response cache
     * @param aiExecuted       actual AI API calls made
     * @param aiFailed         AI calls that resulted in an error
     * @param generatedAt      timestamp of this report
     * @param skipRatePct      percentage of requests skipped by gate
     * @param cacheHitRatePct  percentage of requests served from cache
     * @param aiCallRatePct    percentage of requests that called the AI
     * @param estimatedSavedPct percentage of AI calls saved (gate skips + cache hits)
     */
    public record CostReport(
            long totalRequests,
            long skippedByGate,
            long cacheHits,
            long aiExecuted,
            long aiFailed,
            LocalDateTime generatedAt,
            double skipRatePct,
            double cacheHitRatePct,
            double aiCallRatePct,
            double estimatedSavedPct) {}

    // ─── Embedded REST controller ──────────────────────────────────────────────

    /**
     * Exposes the cost report at {@code GET /api/qa/cost/report}.
     */
    @RestController
    @RequestMapping("/api/qa/cost")
    @RequiredArgsConstructor
    static class CostController {

        private final AiCostMonitor aiCostMonitor;

        /** Returns the current cost report as a JSON map. */
        @GetMapping("/report")
        public Map<String, Object> report() {
            var r = aiCostMonitor.generateReport();
            return Map.of(
                    "totalRequests",    r.totalRequests(),
                    "skippedByGate",    r.skippedByGate(),
                    "cacheHits",        r.cacheHits(),
                    "aiExecuted",       r.aiExecuted(),
                    "aiFailed",         r.aiFailed(),
                    "generatedAt",      r.generatedAt().toString(),
                    "skipRatePct",      r.skipRatePct(),
                    "cacheHitRatePct",  r.cacheHitRatePct(),
                    "aiCallRatePct",    r.aiCallRatePct(),
                    "estimatedSavedPct",r.estimatedSavedPct());
        }
    }
}

