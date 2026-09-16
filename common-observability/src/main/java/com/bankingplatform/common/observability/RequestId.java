package com.bankingplatform.common.observability;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The request-correlation identifier shared by the gateway and every service.
 *
 * <p>The value travels in from the edge on {@value #HEADER} and is echoed back
 * on the response, so a single user-visible id ties together the gateway log
 * line, each service log line and any downstream Feign call.
 *
 * <p>The id arrives from outside the system, so it is treated as untrusted
 * input: it is bounded in length and restricted to an unambiguous character
 * set before it is allowed anywhere near a log file. Without that, a caller
 * could inject newlines and forge log entries, or push a megabyte of text
 * through every log line in the request path.
 */
public final class RequestId {

    /** Header carrying the correlation id, in and out. */
    public static final String HEADER = "X-Request-Id";

    /**
     * Key under which the id is published to SLF4J's MDC.
     *
     * <p>Deliberately the same string as the header. Micrometer Tracing carries
     * the id as a baggage field named after the header and mirrors it into the
     * MDC under that name, so matching here means the log pattern reads one key
     * whether the value arrived via baggage or via the filter below.
     */
    public static final String MDC_KEY = HEADER;

    /**
     * Deliberately narrow: hex, dashes and underscores. That covers UUIDs and
     * the id formats load balancers and tracing systems generate, while
     * excluding whitespace, control characters and anything that could break
     * a log line into two.
     */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private RequestId() {
    }

    /** True when {@code candidate} is safe to propagate and log as-is. */
    public static boolean isValid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }

    /** A fresh id, used when the caller supplied none or supplied a bad one. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * The id to use for a request that arrived carrying {@code incoming}.
     *
     * <p>A valid id is preserved so a correlation started upstream survives;
     * anything else is replaced rather than rejected, because a malformed
     * header is not a reason to fail a banking request.
     */
    public static String resolve(String incoming) {
        return isValid(incoming) ? incoming : generate();
    }
}
