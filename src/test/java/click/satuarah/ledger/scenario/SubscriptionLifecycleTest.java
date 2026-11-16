package click.satuarah.ledger.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import click.satuarah.ledger.application.LedgerService;
import click.satuarah.ledger.application.MutableClock;
import click.satuarah.ledger.domain.AccountType;
import click.satuarah.ledger.domain.ErrorCode;
import click.satuarah.ledger.domain.LedgerException;
import click.satuarah.ledger.domain.NewAccount;
import click.satuarah.ledger.domain.NewTransaction;
import click.satuarah.ledger.domain.Posting;
import click.satuarah.ledger.domain.ReversalRequest;
import click.satuarah.ledger.store.PostgresLedgerStore;
import click.satuarah.ledger.support.PostgresSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * A subscription business, month by month, the way its bookkeeper would see it: cash arrives up
 * front, revenue is recognised as the months are served, and a refund unwinds what is left.
 * Amounts are rupiah.
 */
class SubscriptionLifecycleTest {

    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final long THREE_MONTHS = 189_000;
    private static final long ONE_MONTH = 63_000;

    @Test
    @DisplayName("a quarterly plan is paid, recognised monthly, then partly refunded")
    void quarterlyPlan() {
        DataSource dataSource = PostgresSupport.migratedDataSource();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-01T02:00:00Z"));
        LedgerService ledger = new LedgerService(
                new PostgresLedgerStore(JdbcClient.create(dataSource), new DataSourceTransactionManager(dataSource)),
                clock);

        String suffix = "-" + NEXT.incrementAndGet();
        String bank = "bank" + suffix;
        String deferred = "deferred-revenue" + suffix;
        String revenue = "subscription-revenue" + suffix;
        ledger.openAccount(new NewAccount(bank, "Bank", AccountType.ASSET, "IDR", false));
        ledger.openAccount(new NewAccount(deferred, "Deferred revenue", AccountType.LIABILITY, "IDR", false));
        ledger.openAccount(new NewAccount(revenue, "Subscription revenue", AccountType.REVENUE, "IDR", false));

        // 1 Nov: the customer pays for three months. The money is ours, but not yet earned.
        UUID payment = ledger.post(new NewTransaction(
                        "pay-" + UUID.randomUUID(),
                        "Pro plan, 3 months, paid up front",
                        null,
                        List.of(Posting.debit(bank, THREE_MONTHS), Posting.credit(deferred, THREE_MONTHS))))
                .transaction()
                .id();
        assertEquals(THREE_MONTHS, ledger.account(bank).naturalBalance());
        assertEquals(THREE_MONTHS, ledger.account(deferred).naturalBalance());
        assertEquals(0, ledger.account(revenue).naturalBalance());

        // 30 Nov: November is served, so one month moves from owed to earned.
        clock.set(Instant.parse("2026-11-30T17:00:00Z"));
        UUID november =
                ledger.post(recognise("rev-nov-" + UUID.randomUUID(), "November recognised", deferred, revenue))
                        .transaction()
                        .id();
        assertEquals(THREE_MONTHS - ONE_MONTH, ledger.account(deferred).naturalBalance());
        assertEquals(ONE_MONTH, ledger.account(revenue).naturalBalance());

        // 31 Dec: December is served too.
        clock.set(Instant.parse("2026-12-31T17:00:00Z"));
        UUID december =
                ledger.post(recognise("rev-dec-" + UUID.randomUUID(), "December recognised", deferred, revenue))
                        .transaction()
                        .id();
        assertEquals(ONE_MONTH, ledger.account(deferred).naturalBalance());
        assertEquals(2 * ONE_MONTH, ledger.account(revenue).naturalBalance());

        // The books as they stood at the end of November, read back from December.
        assertEquals(
                THREE_MONTHS - ONE_MONTH,
                ledger.balanceAsOf(deferred, Instant.parse("2026-11-30T23:59:59Z")).balance());

        // 5 Jan: the customer asks for a full refund. Reversing the payment first is refused,
        // because it would take 189.000 out of deferred revenue that now holds only 63.000.
        clock.set(Instant.parse("2027-01-05T03:00:00Z"));
        LedgerException refused = assertThrows(
                LedgerException.class,
                () -> ledger.reverse(new ReversalRequest(payment, "refund-" + UUID.randomUUID(), "Customer refund")));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, refused.code());
        assertEquals(THREE_MONTHS, ledger.account(bank).naturalBalance());

        // The bookkeeper unwinds the two months that were recognised, then reverses the payment.
        ledger.reverse(new ReversalRequest(december, "undo-dec-" + UUID.randomUUID(), "Refund: unwind December"));
        ledger.reverse(new ReversalRequest(november, "undo-nov-" + UUID.randomUUID(), "Refund: unwind November"));
        assertEquals(0, ledger.account(revenue).naturalBalance());
        assertEquals(THREE_MONTHS, ledger.account(deferred).naturalBalance());

        ledger.reverse(new ReversalRequest(payment, "refund-" + UUID.randomUUID(), "Customer refund"));
        assertEquals(0, ledger.account(bank).naturalBalance());
        assertEquals(0, ledger.account(deferred).naturalBalance());

        // Nothing was edited or deleted: the statement still shows every step, in order.
        assertEquals(6, ledger.statement(deferred, 0, 50).size());
        assertTrue(ledger.trialBalance().balanced());
        assertTrue(ledger.integrity().healthy());
    }

    private static NewTransaction recognise(String key, String description, String deferred, String revenue) {
        return new NewTransaction(
                key,
                description,
                null,
                List.of(Posting.debit(deferred, ONE_MONTH), Posting.credit(revenue, ONE_MONTH)));
    }
}
