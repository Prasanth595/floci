package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Consumer;

/**
 * The databases behind the clusters. A cluster has none here: it is ready as soon as it is
 * created, and its endpoint is named but not served.
 */
@ApplicationScoped
public class DsqlDataPlane {

    /** Starts the cluster's database, then reports whether it is ready to serve. */
    public void provision(DsqlCluster cluster, Consumer<Boolean> onReady) {
        onReady.accept(true);
    }

    /** Stops the cluster's database and drops its data, then runs {@code onGone}. */
    public void deprovision(DsqlCluster cluster, Runnable onGone) {
        onGone.run();
    }
}
