package io.github.hectorvent.floci.core.common.auth;

import io.github.hectorvent.floci.services.iam.IamService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Whether the session token on a SigV4 request fits the access key that signed it. A temporary key
 * needs the token it was issued with, and a long-term key must come without one, as on AWS. The
 * token counts wherever it rides, inside the signature or not.
 */
public enum SessionTokenCheck {
    VALID,
    /** A temporary key with no token. */
    MISSING,
    /** A token that was not issued with the key, or any token with a long-term key. */
    INVALID;

    public static SessionTokenCheck of(IamService iamService, String accessKeyId, String sessionToken) {
        boolean presented = sessionToken != null && !sessionToken.isBlank();
        if (!IamService.isTemporaryAccessKey(accessKeyId)) {
            return presented ? INVALID : VALID;
        }
        if (!presented) {
            return MISSING;
        }
        String issued = iamService.findSessionToken(accessKeyId).orElse(null);
        // A session stored before Floci recorded tokens has none to compare.
        if (issued == null || MessageDigest.isEqual(issued.getBytes(StandardCharsets.UTF_8),
                sessionToken.getBytes(StandardCharsets.UTF_8))) {
            return VALID;
        }
        return INVALID;
    }
}
