package com.bazaarvoice.emodb.table.db.astyanax;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.hash;
import static java.util.Objects.requireNonNull;

/**
 * Describes a pending table maintenance operation.
 */
class MaintenanceOp implements Comparable<MaintenanceOp> {
    private final String _name;
    private final Instant _when;
    private final MaintenanceType _type;
    private final String _dataCenter;
    private MaintenanceTask _task;

    static MaintenanceOp forMetadata(String name, Instant when, MaintenanceTask task) {
        return new MaintenanceOp(name, when, MaintenanceType.METADATA, "<system>", requireNonNull(task, "task"));
    }

    static MaintenanceOp forData(String name, Instant when, String dataCenter, MaintenanceTask task) {
        return new MaintenanceOp(name, when, MaintenanceType.DATA, Optional.ofNullable(dataCenter).orElse("<n/a>"), requireNonNull(task, "task"));
    }

    static MaintenanceOp reschedule(MaintenanceOp op, Instant when) {
        return new MaintenanceOp(op.getName(), when, op.getType(), op.getDataCenter(), op.getTask());
    }

    private MaintenanceOp(String name, Instant when, MaintenanceType type, String dataCenter, MaintenanceTask task) {
        _name = requireNonNull(name, "name");
        _when = requireNonNull(when, "when");
        _type = requireNonNull(type, "type");
        _dataCenter = dataCenter;
        _task = task;
    }

    String getName() {
        return _name;
    }

    /**
     * Returns the earliest time this maintenance should occur.
     */
    Instant getWhen() {
        return _when;
    }

    /**
     * Returns whether this maintenance is on the table metadata or on the table data.  It may not be both.
     */
    MaintenanceType getType() {
        return _type;
    }

    /**
     * Returns the data center the maintenance should be performed in, if this maintenance is on table data.
     */
    String getDataCenter() {
        return _dataCenter;
    }

    MaintenanceTask getTask() {
        return _task;
    }

    void clearTask() {
        _task = null;
    }

    @Override
    public int compareTo(MaintenanceOp o) {
        return _when.compareTo(o.getWhen());
    }

    @Override
    public String toString() {
        return _name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MaintenanceOp)) {
            return false;
        }
        MaintenanceOp that = (MaintenanceOp) o;
        return _name.equals(that._name) &&
                _when.equals(that._when) &&
                _type == that._type &&
                Objects.equals(_dataCenter, that._dataCenter);
    }

    @Override
    public int hashCode() {
        return hash(_name, _when, _type, _dataCenter);
    }
}
