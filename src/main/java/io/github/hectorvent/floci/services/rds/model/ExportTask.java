package io.github.hectorvent.floci.services.rds.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A snapshot export to Amazon S3, as {@code StartExportTask} and {@code DescribeExportTasks}
 * describe it. {@code IamRoleArn} and {@code KmsKeyId} are kept as given: Floci writes to its own
 * S3 without assuming the role or encrypting with the key.
 */
@RegisterForReflection
public class ExportTask {

    public static final String STATUS_STARTING = "STARTING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELING = "CANCELING";
    public static final String STATUS_CANCELED = "CANCELED";

    public static final String SOURCE_TYPE_SNAPSHOT = "SNAPSHOT";

    @JsonProperty("ExportTaskIdentifier")
    private String exportTaskIdentifier;

    @JsonProperty("SourceArn")
    private String sourceArn;

    @JsonProperty("SourceType")
    private String sourceType;

    @JsonProperty("ExportOnly")
    private List<String> exportOnly = new ArrayList<>();

    @JsonProperty("SnapshotTime")
    private Instant snapshotTime;

    @JsonProperty("TaskStartTime")
    private Instant taskStartTime;

    @JsonProperty("TaskEndTime")
    private Instant taskEndTime;

    @JsonProperty("S3Bucket")
    private String s3Bucket;

    @JsonProperty("S3Prefix")
    private String s3Prefix;

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("KmsKeyId")
    private String kmsKeyId;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("PercentProgress")
    private int percentProgress;

    @JsonProperty("TotalExtractedDataInGB")
    private int totalExtractedDataInGb;

    @JsonProperty("FailureCause")
    private String failureCause;

    @JsonProperty("WarningMessage")
    private String warningMessage;

    @JsonProperty("Region")
    private String region;

    /** The account that started the task, which its worker runs as. */
    @JsonProperty("AccountId")
    private String accountId;

    public ExportTask() {}

    @JsonIgnore
    public boolean isTerminal() {
        return STATUS_COMPLETE.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELED.equals(status);
    }

    public String getExportTaskIdentifier() { return exportTaskIdentifier; }
    public void setExportTaskIdentifier(String exportTaskIdentifier) { this.exportTaskIdentifier = exportTaskIdentifier; }

    public String getSourceArn() { return sourceArn; }
    public void setSourceArn(String sourceArn) { this.sourceArn = sourceArn; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public List<String> getExportOnly() { return exportOnly; }
    public void setExportOnly(List<String> exportOnly) {
        this.exportOnly = exportOnly != null ? new ArrayList<>(exportOnly) : new ArrayList<>();
    }

    public Instant getSnapshotTime() { return snapshotTime; }
    public void setSnapshotTime(Instant snapshotTime) { this.snapshotTime = snapshotTime; }

    public Instant getTaskStartTime() { return taskStartTime; }
    public void setTaskStartTime(Instant taskStartTime) { this.taskStartTime = taskStartTime; }

    public Instant getTaskEndTime() { return taskEndTime; }
    public void setTaskEndTime(Instant taskEndTime) { this.taskEndTime = taskEndTime; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getS3Prefix() { return s3Prefix; }
    public void setS3Prefix(String s3Prefix) { this.s3Prefix = s3Prefix; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getPercentProgress() { return percentProgress; }
    public void setPercentProgress(int percentProgress) { this.percentProgress = percentProgress; }

    public int getTotalExtractedDataInGb() { return totalExtractedDataInGb; }
    public void setTotalExtractedDataInGb(int totalExtractedDataInGb) { this.totalExtractedDataInGb = totalExtractedDataInGb; }

    public String getFailureCause() { return failureCause; }
    public void setFailureCause(String failureCause) { this.failureCause = failureCause; }

    public String getWarningMessage() { return warningMessage; }
    public void setWarningMessage(String warningMessage) { this.warningMessage = warningMessage; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
}
