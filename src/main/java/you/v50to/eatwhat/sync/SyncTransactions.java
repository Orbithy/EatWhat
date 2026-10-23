package you.v50to.eatwhat.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/** All atomic promises concern this one JDBC DataSource, never Redis, files or a remote service. */
public final class SyncTransactions {
    private final DataSource dataSource;
    private final TransactionTemplate required;
    private final TransactionTemplate mandatory;
    private final you.v50to.eatwhat.cluster.ClusterBarrier clusterBarrier;

    public SyncTransactions(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        dataSource = Objects.requireNonNull(jdbc.getDataSource(), "DataSource");
        clusterBarrier=new you.v50to.eatwhat.cluster.ClusterBarrier(jdbc);
        if (!(manager instanceof DataSourceTransactionManager jdbcManager)
                || jdbcManager.getDataSource() != dataSource) {
            throw new IllegalArgumentException("Sync transactions require the same JDBC DataSource and transaction manager");
        }
        required = new TransactionTemplate(manager);
        required.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        required.setTimeout(30);
        mandatory = new TransactionTemplate(manager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    public <T> T required(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Cannot join a business transaction on a different DataSource");
        }
        return required.execute(status -> { requireLocalWritableTransaction(); return action.get(); });
    }

    public void requireSameDataSource(JdbcTemplate jdbc) {
        if (jdbc.getDataSource() != dataSource) {
            throw new IllegalArgumentException("Event service and transaction boundary must share one DataSource");
        }
    }

    public <T> T mandatory(Supplier<T> action) {
        return mandatory.execute(status -> { requireLocalWritableTransaction(); return action.get(); });
    }

    /** Returns only after its own commit; callers cannot accidentally send an ACK before commit. */
    public <T> T independent(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("This operation requires a standalone committed transaction");
        }
        return required(action);
    }

    private void requireLocalWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("A writable transaction on the event DataSource is required");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("Event foundation requires a writable READ_COMMITTED transaction");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot verify event transaction", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
        clusterBarrier.requireWritable();
    }
}
