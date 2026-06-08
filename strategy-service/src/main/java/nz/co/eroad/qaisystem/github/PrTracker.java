package nz.co.eroad.qaisystem.github;

import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.TestScript;

import java.util.Optional;

/**
 * Tracks open QA-generated Pull Requests.
 * This interface is defined in common; implementations: InMemoryPrTracker, RedisPrTracker.
 * Strategy-service uses InMemoryPrTracker by default (no Redis required for local dev).
 */
public interface PrTracker {
    void trackBdd(String branchName, int prNumber, BddScenario scenario);
    void trackTest(String branchName, int prNumber, TestScript script);
    Optional<PrRecord> findByBranch(String branchName);
    void remove(String branchName);
    int size();
}
