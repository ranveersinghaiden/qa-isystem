package nz.co.eroad.qaisystem.github;

/**
 * @deprecated GitHub diff fetching has been removed from pr-service.
 *             The caller is now responsible for supplying the raw diff in the
 *             {@code raw_diff} field of the webhook / submit payload.
 *             This class is retained only to avoid a build break from stale
 *             git history — it is safe to delete.
 */
@Deprecated(since = "2.0", forRemoval = true)
public class GitHubDiffFetcher {
    // intentionally empty
}
