package ca.pkay.rcloneexplorer.rclone;

/**
 * Parses the target of wrapping rclone backends such as crypt, alias and cache.
 *
 * <p>rclone accepts local paths, named remotes, named connection strings with
 * option overrides, and on-the-fly backends. Keeping this parsing independent
 * from Android makes the accepted forms explicit and easy to verify.</p>
 */
public final class RemoteReferenceParser {

    public enum Kind {
        LOCAL,
        NAMED_REMOTE,
        INLINE_BACKEND,
        UNKNOWN
    }

    public static final class Result {
        private final Kind kind;
        private final String value;

        private Result(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        public Kind getKind() {
            return kind;
        }

        /**
         * Returns the referenced config name for {@link Kind#NAMED_REMOTE}, or
         * the backend name for {@link Kind#INLINE_BACKEND}.
         */
        public String getValue() {
            return value;
        }
    }

    private RemoteReferenceParser() {}

    public static Result parse(String rawTarget) {
        String target = rawTarget == null ? "" : rawTarget.trim();
        if (target.isEmpty()) {
            return new Result(Kind.UNKNOWN, "");
        }

        if (isLocalTarget(target)) {
            return new Result(Kind.LOCAL, target);
        }

        if (target.startsWith(":")) {
            String backend = getInlineBackend(target);
            return backend.isEmpty()
                    ? new Result(Kind.UNKNOWN, "")
                    : new Result(Kind.INLINE_BACKEND, backend);
        }

        String remoteName = getNamedRemote(target);
        return remoteName.isEmpty()
                ? new Result(Kind.UNKNOWN, "")
                : new Result(Kind.NAMED_REMOTE, remoteName);
    }

    private static boolean isLocalTarget(String target) {
        if (target.indexOf(':') < 0) {
            // This mirrors rclone's fspath parser: a path without ':' is local,
            // whether it is absolute or relative.
            return true;
        }

        // Mirror rclone's initial path state: a slash before the first ':' or ','
        // makes this a local path. Once a comma is encountered, later slashes may
        // belong to a connection-string option value and must not change the kind.
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '/' || c == '\\') {
                return true;
            }
            if (c == ',' || c == ':') {
                // Keep Windows drive-letter paths valid when configs are shared.
                return c == ':' && i == 1 && Character.isLetter(target.charAt(0));
            }
        }
        return true;
    }

    private static String getInlineBackend(String target) {
        if (target.length() <= 1) {
            return "";
        }

        int comma = target.indexOf(',', 1);
        int colon = target.indexOf(':', 1);
        int end;
        if (comma < 0) {
            end = colon;
        } else if (colon < 0) {
            end = comma;
        } else {
            end = Math.min(comma, colon);
        }

        if (end <= 1) {
            return "";
        }
        return target.substring(1, end).trim();
    }

    private static String getNamedRemote(String target) {
        int colon = target.indexOf(':');
        if (colon <= 0) {
            return "";
        }

        // Named connection strings can override options before the path, e.g.
        // remote,server_side_across_configs=true:path. The config name is the
        // portion before the first comma.
        int comma = target.indexOf(',');
        int end = comma >= 0 && comma < colon ? comma : colon;
        return target.substring(0, end).trim();
    }
}
