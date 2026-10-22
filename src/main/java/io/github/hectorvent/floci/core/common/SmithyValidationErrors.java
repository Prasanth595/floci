package io.github.hectorvent.floci.core.common;

import java.util.List;

/**
 * The message AWS's Smithy validation layer answers when a request breaks its model's constraints:
 * "1 validation error detected: a" or "N validation errors detected: a; b", each violation reading
 * "Value at 'path' failed to satisfy constraint: ...". The wording is Smithy's, shared by every
 * service built on it; only the error code differs by protocol.
 */
public final class SmithyValidationErrors {

    private SmithyValidationErrors() {}

    /**
     * The message for one or more violations. An empty list is the caller's bug, so it fails rather
     * than answering "0 validation errors"; check {@code isEmpty()} before throwing.
     */
    public static String message(List<String> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("no violations to report");
        }
        String count = violations.size() == 1
                ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        return count + String.join("; ", violations);
    }

    /** The violation for a required member the request leaves out. */
    public static String notNull(String path) {
        return "Value null at '" + path + "' failed to satisfy constraint: Member must not be null";
    }

    /** The 400 error carrying {@link #message} under the protocol's validation error code. */
    public static AwsException exception(String errorCode, List<String> violations) {
        return new AwsException(errorCode, message(violations), 400);
    }

    public static AwsException exception(String errorCode, String violation) {
        return exception(errorCode, List.of(violation));
    }

    /** The code most JSON services use, on both the awsJson and the restJson protocols. */
    public static AwsException validationException(List<String> violations) {
        return exception("ValidationException", violations);
    }

    public static AwsException validationException(String violation) {
        return validationException(List.of(violation));
    }

    /** The awsQuery protocol's code. */
    public static AwsException validationError(List<String> violations) {
        return exception("ValidationError", violations);
    }

    public static AwsException validationError(String violation) {
        return validationError(List.of(violation));
    }

    /** The code of REST JSON services that model their validation failure as BadRequestException. */
    public static AwsException badRequest(List<String> violations) {
        return exception("BadRequestException", violations);
    }

    public static AwsException badRequest(String violation) {
        return badRequest(List.of(violation));
    }
}
