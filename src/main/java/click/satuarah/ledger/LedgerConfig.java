package click.satuarah.ledger;

import click.satuarah.ledger.application.LedgerService;
import click.satuarah.ledger.application.LedgerStore;
import click.satuarah.ledger.store.PostgresLedgerStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Wires the framework-free core to Spring. */
@Configuration(proxyBeanMethods = false)
class LedgerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    LedgerStore ledgerStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new PostgresLedgerStore(jdbc, transactions);
    }

    @Bean
    LedgerService ledgerService(LedgerStore store, Clock clock) {
        return new LedgerService(store, clock);
    }
}
