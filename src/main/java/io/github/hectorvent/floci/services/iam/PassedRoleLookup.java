package io.github.hectorvent.floci.services.iam;

import java.util.List;
import java.util.Optional;

/**
 * Looks up what a stored resource holds that the {@code iam:PassRole} check of a request needs, for
 * the operations that pass a role the request does not name itself: an ECS task started from a task
 * definition runs with the definition's roles, and a self-managed StackSet that names no
 * administration role uses the default one.
 *
 * <p>Implemented by the services that own those resources, each answering for its own and leaving
 * the default for the rest; consumed lazily by {@code IamEnforcementFilter} via
 * {@code Instance<PassedRoleLookup>} so IAM never depends on them directly. Lookups are made in the
 * request's account.</p>
 */
public interface PassedRoleLookup {

    /**
     * The task role and the task execution role of the ECS task definition {@code reference} names in
     * {@code region}, a family, a {@code family:revision} or an ARN, as the definition stores them;
     * empty when there is no such definition or it names neither.
     */
    default List<String> taskDefinitionRoles(String reference, String region) {
        return List.of();
    }

    /** The permission model of the StackSet named {@code stackSetName}; empty when there is none. */
    default Optional<String> stackSetPermissionModel(String stackSetName) {
        return Optional.empty();
    }
}
