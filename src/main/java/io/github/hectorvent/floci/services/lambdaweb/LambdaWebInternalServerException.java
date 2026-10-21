package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.AwsException;

/** An internal error. AWS answers it with the type in the header and the body {@code {}}. */
public final class LambdaWebInternalServerException extends AwsException {

    LambdaWebInternalServerException() {
        super("InternalServerException", null, 500);
    }
}
