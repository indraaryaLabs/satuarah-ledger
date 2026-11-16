package click.satuarah.ledger.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import click.satuarah.ledger.application.LedgerService;
import click.satuarah.ledger.domain.AccountType;
import click.satuarah.ledger.domain.NewAccount;
import click.satuarah.ledger.domain.NewTransaction;
import click.satuarah.ledger.domain.Posting;
import click.satuarah.ledger.support.PostgresSupport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database is the last line of defence. These tests go around the application on purpose
 * and prove PostgreSQL itself refuses to hold a broken ledger.
 */
class PostgresInvariantsTest {

    private static final AtomicInteger NEXT = new AtomicInteger();

    private JdbcClient jdbc;
    private TransactionTemplate transaction;
    private LedgerService ledger;
    private String cash;
    private String revenue;
    private UUID existing;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresSupport.migratedDataSource();
        jdbc = JdbcClient.create(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        ledger = new LedgerService(new PostgresLedgerStore(jdbc, manager), Clock.systemUTC());

        int n = NEXT.incrementAndGet();
        cash = "inv-cash-" + n;
        revenue = "inv-rev-" + n;
        ledger.openAccount(new NewAccount(cash, "Cash", AccountType.ASSET, "IDR", false));
        ledger.openAccount(new NewAccount(revenue, "Revenue", AccountType.REVENUE, "IDR", false));
        existing = ledger.post(new NewTransaction(
                        "inv-key-" + UUID.randomUUID(),
                        "Opening sale",
                        null,
                        List.of(Posting.debit(cash, 1_000), Posting.credit(revenue, 1_000))))
                .transaction()
                .id();
    }

    private void insertTransaction(UUID id) {
        Instant now = Instant.now();
        jdbc.sql("insert into ledger_transaction (id, idempotency_key, fingerprint, description, currency,"
                        + " occurred_at, committed_at) values (?, ?, 'f', 'raw insert', 'IDR', ?, ?)")
                .params(id, "raw-" + id, Timestamp.from(now), Timestamp.from(now))
                .update();
    }

    private void insertLine(UUID id, int lineNo, String account, String side, long amount) {
        jdbc.sql("insert into ledger_entry (transaction_id, line_no, account_code, side, amount, balance_after)"
                        + " values (?, ?, ?, ?, ?, 0)")
                .params(id, lineNo, account, side, amount)
                .update();
    }

    @Test
    @DisplayName("an entry of a recorded transaction cannot be updated")
    void entriesCannotBeUpdated() {
        DataAccessException failure = assertThrows(
                DataAccessException.class,
                () -> jdbc.sql("update ledger_entry set amount = amount + 1 where transaction_id = ?")
                        .param(existing)
                        .update());
        assertTrue(failure.getMessage().contains("append-only"), failure.getMessage());
    }

    @Test
    @DisplayName("an entry of a recorded transaction cannot be deleted")
    void entriesCannotBeDeleted() {
        assertThrows(
                DataAccessException.class,
                () -> jdbc.sql("delete from ledger_entry where transaction_id = ?").param(existing).update());
    }

    @Test
    @DisplayName("a recorded transaction cannot be rewritten or removed")
    void transactionsCannotBeChanged() {
        assertThrows(
                DataAccessException.class,
                () -> jdbc.sql("update ledger_transaction set description = 'edited' where id = ?")
                        .param(existing)
                        .update());
        assertThrows(
                DataAccessException.class,
                () -> jdbc.sql("delete from ledger_transaction where id = ?").param(existing).update());
    }

    @Test
    @DisplayName("the history cannot be truncated")
    void historyCannotBeTruncated() {
        assertThrows(DataAccessException.class, () -> jdbc.sql("truncate ledger_entry").update());
        assertThrows(DataAccessException.class, () -> jdbc.sql("truncate ledger_transaction cascade").update());
    }

    @Test
    @DisplayName("a transaction whose debits and credits differ cannot be committed")
    void unbalancedTransactionIsRefusedAtCommit() {
        UUID id = UUID.randomUUID();
        DataAccessException failure = assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(s -> {
            insertTransaction(id);
            insertLine(id, 1, cash, "DEBIT", 100);
            insertLine(id, 2, revenue, "CREDIT", 90);
        }));
        assertTrue(failure.getMessage().contains("not balanced"), failure.getMessage());
        assertEquals(0L, jdbc.sql("select count(*) from ledger_transaction where id = ?").param(id).query(Long.class).single());
    }

    @Test
    @DisplayName("a transaction with a single line cannot be committed")
    void singleLineIsRefused() {
        UUID id = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(s -> {
            insertTransaction(id);
            insertLine(id, 1, cash, "DEBIT", 100);
        }));
    }

    @Test
    @DisplayName("a balanced transaction inserted by hand is accepted, one line at a time")
    void balancedRawTransactionIsAccepted() {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(s -> {
            insertTransaction(id);
            insertLine(id, 1, cash, "DEBIT", 100);
            insertLine(id, 2, revenue, "CREDIT", 100);
        });
        assertEquals(2L, jdbc.sql("select count(*) from ledger_entry where transaction_id = ?").param(id).query(Long.class).single());
    }

    @Test
    @DisplayName("a transaction cannot mix currencies")
    void mixedCurrenciesAreRefused() {
        String dollars = "inv-usd-" + NEXT.incrementAndGet();
        ledger.openAccount(new NewAccount(dollars, "Dollars", AccountType.ASSET, "USD", false));
        UUID id = UUID.randomUUID();
        DataAccessException failure = assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(s -> {
            insertTransaction(id);
            insertLine(id, 1, dollars, "DEBIT", 100);
            insertLine(id, 2, revenue, "CREDIT", 100);
        }));
        assertTrue(failure.getMessage().contains("mixes currencies"), failure.getMessage());
    }

    @Test
    @DisplayName("an account that forbids it cannot be driven to the wrong side of zero")
    void overdraftIsRefusedByTheDatabase() {
        assertThrows(
                DataAccessException.class,
                () -> jdbc.sql("update account set balance = -1 where code = ?").param(cash).update());
    }

    @Test
    @DisplayName("an amount of zero or less is refused")
    void nonPositiveAmountIsRefused() {
        UUID id = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(s -> {
            insertTransaction(id);
            insertLine(id, 1, cash, "DEBIT", 0);
        }));
    }
}
