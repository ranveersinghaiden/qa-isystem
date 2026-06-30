package nz.co.eroad.qaisystem.oneshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tiny, dependency-free parser for the one-shot CLI flags shared by every {@code *OneShotRunner}.
 * Supported flags: {@code --mode}, {@code --pr-id}, {@code --scenario}, {@code --per-pod},
 * {@code --review-id}, {@code --target-dir}. Unknown flags are ignored so each runner can add its
 * own without breaking the others.
 *
 * <p>{@link #isOneShot(String[])} is the single switch the {@code main()} methods consult to decide
 * whether to activate the {@code oneshot} profile — when {@code --mode} is absent the normal
 * always-on Kafka path runs unchanged.
 */
public final class OneShotArgs {

    private static final String MODE = "--mode";
    private static final String PR_ID = "--pr-id";
    private static final String SCENARIO = "--scenario";
    private static final String PER_POD = "--per-pod";
    private static final String REVIEW_ID = "--review-id";
    private static final String TARGET_DIR = "--target-dir";

    private final Map<String, String> values;

    private OneShotArgs(Map<String, String> values) {
        this.values = values;
    }

    /** Parses {@code --key value} pairs (and bare {@code --key} flags) into an {@link OneShotArgs}. */
    public static OneShotArgs parse(String[] args) {
        Map<String, String> map = new HashMap<>();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a != null && a.startsWith("--")) {
                    String next = (i + 1 < args.length) ? args[i + 1] : null;
                    if (next != null && !next.startsWith("--")) {
                        map.put(a, next);
                        i++;
                    } else {
                        map.put(a, "");
                    }
                }
            }
        }
        return new OneShotArgs(map);
    }

    /** True when {@code --mode} is present — the trigger for one-shot (non-Kafka) execution. */
    public static boolean isOneShot(String[] args) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (MODE.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code --mode} value (e.g. {@code oneshot}, {@code list-scenarios}, {@code gather}), or empty. */
    public String mode() {
        return values.getOrDefault(MODE, "");
    }

    public Optional<String> prId() {
        return opt(PR_ID);
    }

    public Optional<String> scenario() {
        return opt(SCENARIO);
    }

    public Optional<String> reviewId() {
        return opt(REVIEW_ID);
    }

    public Optional<String> targetDir() {
        return opt(TARGET_DIR);
    }

    /** {@code --per-pod} group size for the dynamic matrix; defaults to 1 (one scenario per cell). */
    public int perPod() {
        String raw = values.get(PER_POD);
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return Math.max(n, 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private Optional<String> opt(String key) {
        String v = values.get(key);
        return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
    }
}
