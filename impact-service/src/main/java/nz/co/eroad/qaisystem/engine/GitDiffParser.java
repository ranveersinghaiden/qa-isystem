package nz.co.eroad.qaisystem.engine;

/**
 * @deprecated Moved to {@link nz.co.eroad.qaisystem.parser.GitDiffParser} in the
 *             {@code common} module so all services share a single implementation.
 *             This bridge exists only to preserve source compatibility — please
 *             update any remaining references to use the common package directly.
 */
@Deprecated(since = "2.0", forRemoval = true)
public class GitDiffParser extends nz.co.eroad.qaisystem.parser.GitDiffParser {
    // intentionally empty — all behaviour is inherited from common
}