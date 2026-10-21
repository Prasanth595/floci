package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The changes to one table since point-in-time recovery was enabled, oldest first, and the
 * earliest time they can restore the table to.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PointInTimeHistory {

    private Instant start;
    private List<PointInTimeChange> changes;

    public PointInTimeHistory() {
        this.changes = new ArrayList<>();
    }

    public PointInTimeHistory(Instant start, List<PointInTimeChange> changes) {
        this.start = start;
        this.changes = changes;
    }

    public PointInTimeHistory copy() {
        return new PointInTimeHistory(start, new ArrayList<>(changes));
    }

    public Instant getStart() { return start; }
    public void setStart(Instant start) { this.start = start; }

    public List<PointInTimeChange> getChanges() { return changes; }
    public void setChanges(List<PointInTimeChange> changes) {
        this.changes = changes != null ? changes : new ArrayList<>();
    }
}
