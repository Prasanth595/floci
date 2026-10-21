package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backup ARNs: {@code <table ARN>/backup/<14-digit epoch millis>-<8 hex digits>}.
 */
final class DynamoDbBackupArns {

    private static final Pattern RESOURCE = Pattern.compile("table/([a-zA-Z0-9_.\\-]+)/backup/([^/]{3,})");
    private static final Pattern BACKUP_ID = Pattern.compile("\\d{14}-[0-9a-fA-F]{8}");

    private DynamoDbBackupArns() {}

    record BackupArn(String arn, String region, String accountId, String tableName, long creationMillis) {}

    /**
     * AWS rejects an ARN it cannot split into a table and a backup id with "Invalid Backup ARN", and
     * one that splits but names another service, an unknown region or a malformed id with
     * "Invalid Request: BackupArn is not valid".
     */
    static BackupArn parse(String input) {
        if (input == null) {
            throw invalidBackupArn();
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(input);
        } catch (IllegalArgumentException e) {
            throw invalidBackupArn();
        }
        Matcher resource = RESOURCE.matcher(arn.resource());
        if (!resource.matches()) {
            throw invalidBackupArn();
        }
        String backupId = resource.group(2);
        if (!"dynamodb".equals(arn.service()) || !AwsRegions.isRegionId(arn.region())
                || !BACKUP_ID.matcher(backupId).matches()) {
            throw new AwsException("ValidationException", "Invalid Request: BackupArn is not valid", 400);
        }
        long creationMillis = Long.parseLong(backupId.substring(0, 14));
        return new BackupArn(input, arn.region(), arn.accountId(), resource.group(1), creationMillis);
    }

    static String mint(String tableArn, Instant creationDateTime) {
        return tableArn + "/backup/" + String.format("%014d", creationDateTime.toEpochMilli())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** AWS ends the id of a system backup with zeros instead of random hex digits. */
    static String mintSystem(String tableArn, Instant creationDateTime) {
        return tableArn + "/backup/" + String.format("%014d", creationDateTime.toEpochMilli()) + "-00000000";
    }

    private static AwsException invalidBackupArn() {
        return new AwsException("ValidationException", "Invalid Backup ARN", 400);
    }
}
