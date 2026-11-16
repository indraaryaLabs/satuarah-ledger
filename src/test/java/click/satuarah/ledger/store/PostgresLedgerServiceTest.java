package click.satuarah.ledger.store;

import click.satuarah.ledger.application.LedgerServiceContract;
import click.satuarah.ledger.application.LedgerStore;
import click.satuarah.ledger.support.PostgresSupport;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** The same contract the in-memory store passes, now against real PostgreSQL. */
class PostgresLedgerServiceTest extends LedgerServiceContract {

    @Override
    protected LedgerStore newStore() {
        DataSource dataSource = PostgresSupport.migratedDataSource();
        return new PostgresLedgerStore(JdbcClient.create(dataSource), new DataSourceTransactionManager(dataSource));
    }
}
