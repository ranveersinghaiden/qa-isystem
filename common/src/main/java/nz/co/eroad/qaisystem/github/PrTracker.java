package nz.co.eroad.qaisystem.github;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.TestScript;
import java.util.Optional;
/**
 * Tracks open QA-generated Pull Requests so the GitHub webhook can route
 * merge/rejection events to the correct handler.
 *
 * Implementations:
 *   InMemoryPrTracker — default; survives only while the JVM is running.
 *   RedisPrTracker    — durable across pod restarts when spring.data.redis.host is set.
 */
public interface PrTracker {
    void trackBdd(String branchName, int prNumber, BddScenario scenario);
    void trackTest(String branchName, int prNumber, TestScript script);
    Optional<PrRecord> findByBranch(String branchName);
    void remove(String branchName);
    int size();
}
