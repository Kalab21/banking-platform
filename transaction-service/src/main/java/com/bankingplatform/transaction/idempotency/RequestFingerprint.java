package com.bankingplatform.transaction.idempotency;

import com.bankingplatform.common.security.CallerIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * A stable hash of "who asked for what", used to tell a genuine retry from a
 * key reused for a different operation.
 *
 * <p>Two properties are load-bearing:
 *
 * <ul>
 *   <li><b>The caller is part of the hash.</b> Without it, a key another
 *       customer had used would resolve to that customer's stored result. The
 *       ownership check already runs first and would refuse the request, but an
 *       identifier that silently spans principals is the wrong shape for a
 *       cache of financial outcomes.</li>
 *   <li><b>Amounts are normalised before hashing.</b> {@code 100} and
 *       {@code 100.00} are the same amount of money and must not look like two
 *       different requests, or an honest retry that re-serialises its own
 *       payload would be rejected with a 409.</li>
 * </ul>
 *
 * <p>This is deliberately <em>not</em> the idempotency key. Deriving the key
 * from the request would make two legitimate identical transfers — the same
 * amount, the same accounts, a minute apart — indistinguishable, and the second
 * would silently vanish.
 */
final class RequestFingerprint {

    /**
     * Reads JSON numbers back as {@link BigDecimal} rather than {@code double},
     * so normalisation sees the value that was sent instead of a binary
     * approximation of it.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private RequestFingerprint() {
    }

    static String of(String operation, CallerIdentity caller, Object request) {
        Map<String, Object> fields =
                MAPPER.convertValue(request, new TypeReference<TreeMap<String, Object>>() {
                });

        StringBuilder canonical = new StringBuilder()
                .append("op=").append(operation)
                .append("&caller=").append(caller == null ? "anonymous" : caller.userId());

        // TreeMap iteration is by key, so field declaration order in the DTO
        // cannot change the fingerprint.
        fields.forEach((name, value) -> {
            if (value != null) {
                canonical.append('&').append(name).append('=').append(normalise(value));
            }
        });

        return sha256(canonical.toString());
    }

    private static String normalise(Object value) {
        if (value instanceof BigDecimal decimal) {
            // toPlainString after stripping zeros: 100.00, 100.0 and 100 all
            // become "100", and none of them become "1E+2".
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
