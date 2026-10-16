package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;

/** The same locally owned group identity for direct tag authorization and execution. */
public record LogGroupTagTarget(String name, String arn) {

    public static boolean supports(String action) {
        return "logs:TagResource".equals(action) || "logs:UntagResource".equals(action)
                || "logs:ListTagsForResource".equals(action);
    }

    public static LogGroupTagTarget resolve(String input, String region, String account, String partition) {
        String prefix = new AwsArnUtils.Arn(partition, "logs", region, account, "log-group:").toString();
        if (input.startsWith("arn:") && !input.startsWith(prefix)) {
            throw new AwsException("ResourceNotFoundException", "The specified log group does not exist.", 400);
        }
        // Retain the handler's existing name/suffix coercion; do not add unrelated input restrictions.
        String name = input.startsWith(prefix) ? input.substring(prefix.length())
                : CloudWatchLogsHandler.extractLogGroupNameFromArn(input);
        if (input.startsWith(prefix) && name.endsWith(":*")) {
            name = name.substring(0, name.length() - 2);
        }
        return new LogGroupTagTarget(name,
                new AwsArnUtils.Arn(partition, "logs", region, account, "log-group:" + name).toString());
    }
}
