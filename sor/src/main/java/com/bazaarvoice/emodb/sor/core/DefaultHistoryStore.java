package com.bazaarvoice.emodb.sor.core;

import com.bazaarvoice.emodb.sor.api.Change;
import com.bazaarvoice.emodb.sor.api.History;
import com.bazaarvoice.emodb.sor.api.ReadConsistency;
import com.bazaarvoice.emodb.sor.api.WriteConsistency;
import com.bazaarvoice.emodb.sor.db.DataReaderDAO;
import com.bazaarvoice.emodb.sor.db.DataWriterDAO;
import com.bazaarvoice.emodb.sor.db.Key;
import com.bazaarvoice.emodb.table.db.TableDAO;
import com.google.inject.Inject;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class DefaultHistoryStore implements HistoryStore {

    private final TableDAO _tableDao;
    private final DataReaderDAO _dataReaderDao;
    private final DataWriterDAO _dataWriterDao;
    private final Duration _historyTtl;

    @Inject
    public DefaultHistoryStore(TableDAO tableDAO, DataReaderDAO dataReaderDao, DataWriterDAO dataWriterDao,
                               @DeltaHistoryTtl Duration historyTtl) {
        _tableDao = requireNonNull(tableDAO, "tableDAO");
        _dataReaderDao = requireNonNull(dataReaderDao, "dataReaderDao");
        _dataWriterDao = requireNonNull(dataWriterDao, "dataWriterDao");
        _historyTtl = requireNonNull(historyTtl, "historyTtl");
    }

    @Override
    public Iterator<Change> getDeltaHistories(String table, String rowId) {
        Key key = new Key(_tableDao.get(table), rowId);
        return _dataReaderDao.getExistingHistories(key, null, null, ReadConsistency.STRONG);
    }

    @Override
    public void putDeltaHistory(String table, String rowId, List<History> histories) {
        // Take the performance improvement over saving delta history. It's ok if we lose delta history.
        _dataWriterDao.storeCompactedDeltas(_tableDao.get(table), rowId, histories, WriteConsistency.NON_DURABLE);
    }

    @Override
    public void putDeltaHistory(Object rowId, List<History> deltaAudits, HistoryBatchPersister historyBatchPersister) {
        historyBatchPersister.commit(deltaAudits, rowId);
    }

    @Override
    public Duration getHistoryTtl() {
        return _historyTtl;
    }

    @Override
    public boolean isDeltaHistoryEnabled() {
        return !Duration.ZERO.equals(getHistoryTtl());
    }
}
