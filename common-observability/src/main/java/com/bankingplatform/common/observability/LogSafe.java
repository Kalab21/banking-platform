package com.bankingplatform.common.observability;

/**
 * Neutralises an untrusted value before it reaches a log line.
 *
 * <p>Anything a caller supplies — a request path, an {@code Idempotency-Key},
 * a header — can carry characters that end a log line and begin another. A
 * caller who can do that can forge entries: an audit trail that reads
 * plausibly and describes something that never happened is worse than no log
 * at all, because it will be believed.
 *
 * <p>Two properties, both deliberate:
 *
 * <ul>
 *   <li><b>An allowlist, not a denylist.</b> Stripping {@code \p{Cntrl}} leaves
 *       U+2028 and U+2029, which are not control characters but which several
 *       log viewers and consoles render as line breaks. Permitting a known set
 *       and replacing everything else has no such gap.</li>
 *   <li><b>Bounded.</b> A caller cannot push a megabyte through every log line
 *       in the request path.</li>
 * </ul>
 *
 * <p>The permitted set is the unreserved and path characters of RFC 3986, which
 * covers the values this platform logs — paths, opaque client keys, operation
 * names — without covering whitespace. A value that survives this is a single
 * unambiguous field on the line.
 *
 * <p>This is for log arguments, not for response bodies or persisted data.
 * Values are altered to make them safe to print; nothing here should be treated
 * as a faithful copy of what the caller sent.
 */
public final class LogSafe {

    /** Long enough to identify a value, short enough not to flood a log line. */
    public static final int DEFAULT_MAX_LENGTH = 200;

    /** What a null value prints as, so a log line never reads {@code null}. */
    private static final String ABSENT = "(none)";

    private static final String TRUNCATED = "...";

    private static final String REPLACEMENT = "_";

    /**
     * The characters that actually break a line, named rather than implied.
     *
     * <p>CR and LF are the obvious pair. Vertical tab, form feed and NEL end a
     * line for some readers; U+2028 and U+2029 are not control characters at
     * all, and a {@code \p{Cntrl}} filter would hand them straight through
     * although several log viewers render them as breaks.
     */
    private static final String LINE_BREAKS = "[\\r\\n\\u000B\\f\\u0085\\u2028\\u2029]";

    /**
     * Everything outside the RFC 3986 unreserved and path alphabet. Written as
     * a negated class so that a character is permitted only by being named
     * here, and a character nobody thought about is replaced rather than
     * printed.
     */
    private static final String OUTSIDE_ALPHABET = "[^A-Za-z0-9/._~:@!$&'()*+,;=%-]";

    private LogSafe() {
    }

    /** {@code value(raw, DEFAULT_MAX_LENGTH)}. */
    public static String value(String raw) {
        return value(raw, DEFAULT_MAX_LENGTH);
    }

    /**
     * Returns {@code raw} with every character outside the permitted alphabet
     * replaced, truncated to {@code maxLength}.
     *
     * @param raw       an untrusted value, possibly null
     * @param maxLength characters kept before truncation
     */
    public static String value(String raw, int maxLength) {
        if (raw == null) {
            return ABSENT;
        }
        boolean overLength = raw.length() > maxLength;
        String bounded = overLength ? raw.substring(0, maxLength) : raw;

        // Two passes, in this order and on purpose. The first removes the
        // characters that forge a log entry, named one by one so the threat
        // being addressed is legible in the code and to anything reading it.
        // The second is the allowlist that actually carries the guarantee: it
        // subsumes the first, and covers everything nobody enumerated.
        String withoutBreaks = bounded.replaceAll(LINE_BREAKS, REPLACEMENT);
        String neutralised = withoutBreaks.replaceAll(OUTSIDE_ALPHABET, REPLACEMENT);

        return overLength ? neutralised + TRUNCATED : neutralised;
    }
}
