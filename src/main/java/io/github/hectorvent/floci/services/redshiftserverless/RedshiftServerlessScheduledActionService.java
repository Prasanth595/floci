package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.ScheduledAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redshift Serverless scheduled actions. They are stored and echoed, not run: Floci never takes
 * the snapshot on the schedule, and a cron schedule reports no upcoming invocations.
 *
 * <p>An action belongs to a namespace but lives in its own store, so deleting the namespace needs
 * no hook here. Each read checks that the namespace is still there and drops the action otherwise.
 */
@ApplicationScoped
public class RedshiftServerlessScheduledActionService implements Resettable {

    private static final int MAX_RETENTION_DAYS = 3653;
    private static final int PAGE_SIZE = 100;

    private final AccountAwareStorageBackend<ScheduledAction> actions;
    private final RedshiftServerlessService serverless;

    @Inject
    public RedshiftServerlessScheduledActionService(StorageFactory storageFactory,
                                                    RedshiftServerlessService serverless) {
        this.actions = storageFactory.create("redshiftserverless", "redshiftserverless-scheduledactions.json",
                new TypeReference<Map<String, ScheduledAction>>() {});
        this.serverless = serverless;
    }

    /**
     * Creates the action described by {@code request}. {@code enabled} defaults to true. The target
     * namespace of the snapshot and the owning namespace must both exist.
     */
    public synchronized ScheduledAction createScheduledAction(ScheduledAction request, String region) {
        validateName(request.getScheduledActionName());
        validateNamespaceReference(request.getNamespaceName(), "namespaceName", region);
        ServerlessSupport.requireText(request.getRoleArn(), "roleArn");
        validateSchedule(request);
        validateTarget(request, region);
        validateWindow(request);
        if (live(region).stream().anyMatch(existing ->
                existing.getScheduledActionName().equals(request.getScheduledActionName()))) {
            throw ServerlessSupport.conflict("The scheduled action " + request.getScheduledActionName()
                    + " already exists.");
        }

        ScheduledAction action = new ScheduledAction(request);
        action.setScheduledActionUuid(UUID.randomUUID().toString());
        action.setEnabled(request.getEnabled() == null || request.getEnabled());
        actions.put(storageKey(region, action.getScheduledActionName()), action);
        return new ScheduledAction(action);
    }

    public synchronized ScheduledAction getScheduledAction(String scheduledActionName, String region) {
        return new ScheduledAction(require(scheduledActionName, region));
    }

    public synchronized PaginatedResult<ScheduledAction> listScheduledActions(String namespaceName, String region,
                                                                              Integer maxResults, String nextToken) {
        List<ScheduledAction> matching = live(region).stream()
                .filter(action -> namespaceName == null || namespaceName.equals(action.getNamespaceName()))
                .sorted(Comparator.comparing(ScheduledAction::getScheduledActionName))
                .map(ScheduledAction::new)
                .toList();
        return Pagination.paginate(matching, ScheduledAction::getScheduledActionName, maxResults, nextToken,
                PAGE_SIZE, PAGE_SIZE, "InvalidPaginationException");
    }

    /**
     * Applies the members {@code changes} carries. The schedule and the target replace the stored
     * ones as a whole, because each is a union of which exactly one member may be set.
     */
    public synchronized ScheduledAction updateScheduledAction(ScheduledAction changes, String region) {
        ScheduledAction current = require(changes.getScheduledActionName(), region);
        ScheduledAction updated = new ScheduledAction(current);
        if (changes.getRoleArn() != null) {
            ServerlessSupport.requireText(changes.getRoleArn(), "roleArn");
            updated.setRoleArn(changes.getRoleArn());
        }
        if (changes.getDescription() != null) {
            updated.setDescription(changes.getDescription());
        }
        if (changes.getEnabled() != null) {
            updated.setEnabled(changes.getEnabled());
        }
        if (changes.getStartTime() != null) {
            updated.setStartTime(changes.getStartTime());
        }
        if (changes.getEndTime() != null) {
            updated.setEndTime(changes.getEndTime());
        }
        if (changes.getScheduleAt() != null || changes.getScheduleCron() != null) {
            validateSchedule(changes);
            updated.setScheduleAt(changes.getScheduleAt());
            updated.setScheduleCron(changes.getScheduleCron());
        }
        if (changes.getSnapshotNamePrefix() != null) {
            validateTarget(changes, region);
            updated.setTargetNamespaceName(changes.getTargetNamespaceName());
            updated.setSnapshotNamePrefix(changes.getSnapshotNamePrefix());
            updated.setRetentionPeriod(changes.getRetentionPeriod());
            updated.setSnapshotTags(changes.getSnapshotTags());
        }
        validateWindow(updated);
        actions.put(storageKey(region, updated.getScheduledActionName()), updated);
        return new ScheduledAction(updated);
    }

    public synchronized ScheduledAction deleteScheduledAction(String scheduledActionName, String region) {
        ScheduledAction existing = new ScheduledAction(require(scheduledActionName, region));
        actions.delete(storageKey(region, scheduledActionName));
        return existing;
    }

    @Override
    public void clear() {
        actions.clear();
    }

    private ScheduledAction require(String scheduledActionName, String region) {
        validateName(scheduledActionName);
        ScheduledAction action = actions.get(storageKey(region, scheduledActionName)).orElse(null);
        if (action != null && !serverless.namespaceExists(action.getNamespaceName(), region)) {
            actions.delete(storageKey(region, scheduledActionName));
            action = null;
        }
        if (action == null) {
            throw ServerlessSupport.notFound("The scheduled action " + scheduledActionName + " was not found.");
        }
        return action;
    }

    /** The region's actions whose namespace is still there; any other is deleted as it is found. */
    private List<ScheduledAction> live(String region) {
        List<ScheduledAction> alive = new ArrayList<>();
        for (ScheduledAction action : actions.scan(key -> key.startsWith(region + "::"))) {
            if (serverless.namespaceExists(action.getNamespaceName(), region)) {
                alive.add(action);
            } else {
                actions.delete(storageKey(region, action.getScheduledActionName()));
            }
        }
        return alive;
    }

    private static void validateName(String name) {
        ServerlessSupport.requireText(name, "scheduledActionName");
        if (name.length() < 3 || name.length() > 60 || !ServerlessSupport.LOWER_NAME.matcher(name).matches()) {
            throw ServerlessSupport.validation(
                    "scheduledActionName must be 3 to 60 characters of lower-case letters, digits and hyphens.");
        }
    }

    private void validateNamespaceReference(String namespaceName, String field, String region) {
        ServerlessSupport.requireText(namespaceName, field);
        if (namespaceName.length() < 3 || namespaceName.length() > 64
                || !ServerlessSupport.LOWER_NAME.matcher(namespaceName).matches()) {
            throw ServerlessSupport.validation(field + " must be 3 to 64 characters of lower-case letters, "
                    + "digits and hyphens.");
        }
        if (!serverless.namespaceExists(namespaceName, region)) {
            throw ServerlessSupport.notFound("The namespace " + namespaceName + " was not found.");
        }
    }

    /** Exactly one of {@code at} and {@code cron} must be set: the schedule is a union. */
    private static void validateSchedule(ScheduledAction action) {
        boolean hasAt = action.getScheduleAt() != null;
        boolean hasCron = action.getScheduleCron() != null && !action.getScheduleCron().isBlank();
        if (hasAt == hasCron) {
            throw ServerlessSupport.validation("schedule must set exactly one of at and cron.");
        }
    }

    private void validateTarget(ScheduledAction action, String region) {
        validateNamespaceReference(action.getTargetNamespaceName(), "targetAction.createSnapshot.namespaceName",
                region);
        String prefix = action.getSnapshotNamePrefix();
        if (prefix == null || prefix.isEmpty() || prefix.length() > 235) {
            throw ServerlessSupport.validation(
                    "targetAction.createSnapshot.snapshotNamePrefix must be 1 to 235 characters.");
        }
        Integer retention = action.getRetentionPeriod();
        if (retention != null && retention != -1 && (retention < 1 || retention > MAX_RETENTION_DAYS)) {
            throw ServerlessSupport.validation("targetAction.createSnapshot.retentionPeriod must be -1 "
                    + "(indefinite) or between 1 and " + MAX_RETENTION_DAYS + ".");
        }
    }

    private static void validateWindow(ScheduledAction action) {
        if (action.getStartTime() != null && action.getEndTime() != null
                && !action.getEndTime().isAfter(action.getStartTime())) {
            throw ServerlessSupport.validation("endTime must be after startTime.");
        }
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }
}
