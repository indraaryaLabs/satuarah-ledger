package click.satuarah.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import click.satuarah.ledger.domain.Account;
import click.satuarah.ledger.domain.AccountType;
import click.satuarah.ledger.domain.BalanceAt;
import click.satuarah.ledger.domain.ErrorCode;
import click.satuarah.ledger.domain.IntegrityReport;
import click.satuarah.ledger.domain.LedgerException;
import click.satuarah.ledger.domain.LedgerTransaction;
import click.satuarah.ledger.domain.NewAccount;
import click.satuarah.ledger.domain.NewTransaction;
import click.satuarah.ledger.domain.PostResult;
import click.satuarah.ledger.domain.Posting;
import click.satuarah.ledger.domain.ReversalRequest;
import click.satuarah.ledger.domain.Side;
import click.satuarah.ledger.domain.StatementLine;
import click.satuarah.ledger.domain.TrialBalance;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every rule of the ledger, written once and run against every store. A store that passes this is
 * a correct place to keep the ledger.
 */
public abstract class LedgerServiceContract {

    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");

    protected MutableClock clock;
    protected LedgerService ledger;
    protected LedgerStore store;

    /** A store with whatever data earlier tests left; tests never rely on it being empty. */
    protected abstract LedgerStore newStore();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        store = newStore();
        ledger = new LedgerService(store, clock);
    }

    // ---- helpers --------------------------------------------------------------------------

    /** Account codes unique across the whole test run, so a shared database never collides. */
    protected static String code(String prefix) {
        return prefix + "-" + NEXT.incrementAndGet();
    }

    protected String open(String prefix, AccountType type) {
        return open(prefix, type, "IDR", false);
    }

    protected String open(String prefix, AccountType type, String currency, boolean allowNegative) {
        String code = code(prefix);
        ledger.openAccount(new NewAccount(code, prefix, type, currency, allowNegative));
        return code;
    }

    protected PostResult post(String key, String description, Posting... postings) {
        return ledger.post(new NewTransaction(key, description, null, List.of(postings)));
    }

    protected static String key() {
        return "key-" + UUID.randomUUID();
    }

    protected long natural(String code) {
        return ledger.account(code).naturalBalance();
    }

    protected static ErrorCode refusal(Runnable action) {
        return assertThrows(LedgerException.class, action::run).code();
    }

    /** Puts money in a bank account from an equity account, the way an owner would fund it. */
    protected void fund(String bank, String equity, long amount) {
        post(key(), "Funding", Posting.debit(bank, amount), Posting.credit(equity, amount));
    }

    // ---- accounts -------------------------------------------------------------------------

    @Test
    void aNewAccountStartsEmpty() {
        String bank = open("BANK", AccountType.ASSET);
        Account account = ledger.account(bank);
        assertEquals(0, account.balance());
        assertEquals(AccountType.ASSET, account.type());
        assertEquals("IDR", account.currency());
        assertEquals(START, account.createdAt());
    }

    @Test
    void anAccountCodeCanOnlyBeUsedOnce() {
        String bank = open("BANK", AccountType.ASSET);
        assertEquals(
                ErrorCode.ACCOUNT_EXISTS,
                refusal(() -> ledger.openAccount(new NewAccount(bank, "again", AccountType.ASSET, "IDR", false))));
    }

    @Test
    void anUnknownAccountIsReported() {
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, refusal(() -> ledger.account("NO-SUCH-ACCOUNT")));
    }

    @Test
    void accountsAreListedByCode() {
        String a = open("LIST", AccountType.ASSET);
        String b = open("LIST", AccountType.ASSET);
        List<String> codes = ledger.accounts().stream().map(Account::code).toList();
        assertTrue(codes.contains(a));
        assertTrue(codes.contains(b));
        List<String> sorted = new ArrayList<>(codes);
        sorted.sort(null);
        assertEquals(sorted, codes);
    }

    // ---- posting --------------------------------------------------------------------------

    @Test
    void postingMovesBothSidesAndTheBooksStayBalanced() {
        String bank = open("BANK", AccountType.ASSET);
        String deferred = open("DEFERRED", AccountType.LIABILITY);

        PostResult result = post(key(), "Pro plan, 3 months", Posting.debit(bank, 189_000), Posting.credit(deferred, 189_000));

        assertFalse(result.replayed());
        assertEquals(189_000, natural(bank));
        assertEquals(189_000, natural(deferred));
        assertEquals(189_000, ledger.account(bank).balance());
        assertEquals(-189_000, ledger.account(deferred).balance());
        assertEquals("IDR", result.transaction().currency());
    }

    @Test
    void eachLineRecordsTheBalanceAfterIt() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        fund(bank, equity, 1_000);

        LedgerTransaction tx = post(key(), "Fee", Posting.credit(bank, 300), Posting.debit(open("FEES", AccountType.EXPENSE), 300))
                .transaction();

        assertEquals(2, tx.lines().size());
        assertEquals(1, tx.lines().get(0).lineNo());
        assertEquals(700, tx.lines().get(0).balanceAfter());
        assertEquals(Side.CREDIT, tx.lines().get(0).side());
        assertEquals(300, tx.lines().get(1).balanceAfter());
    }

    @Test
    void sequenceNumbersIncreaseWithEachTransaction() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        long first = post(key(), "one", Posting.debit(bank, 1), Posting.credit(equity, 1)).transaction().sequence();
        long second = post(key(), "two", Posting.debit(bank, 1), Posting.credit(equity, 1)).transaction().sequence();
        assertTrue(second > first);
    }

    @Test
    void aTransactionKeepsWhenItHappenedSeparatelyFromWhenItWasRecorded() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        Instant earlier = Instant.parse("2026-10-30T09:00:00Z");

        LedgerTransaction backdated = ledger.post(new NewTransaction(
                        key(), "Late entry", earlier, List.of(Posting.debit(bank, 5), Posting.credit(equity, 5))))
                .transaction();
        LedgerTransaction now = post(key(), "Now", Posting.debit(bank, 5), Posting.credit(equity, 5)).transaction();

        assertEquals(earlier, backdated.occurredAt());
        assertEquals(START, backdated.committedAt());
        assertEquals(START, now.occurredAt());
    }

    @Test
    void aTransactionIsFoundByItsId() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        LedgerTransaction tx = post(key(), "Find me", Posting.debit(bank, 5), Posting.credit(equity, 5)).transaction();
        assertEquals(tx, ledger.transaction(tx.id()));
        assertEquals(ErrorCode.TRANSACTION_NOT_FOUND, refusal(() -> ledger.transaction(UUID.randomUUID())));
    }

    @Test
    void anUnknownAccountRefusesTheWholeTransaction() {
        String bank = open("BANK", AccountType.ASSET);
        ErrorCode code = refusal(() -> post(key(), "Bad", Posting.debit(bank, 5), Posting.credit("NO-SUCH-ACCOUNT", 5)));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, code);
        assertEquals(0, ledger.account(bank).balance());
    }

    @Test
    void aTransactionCannotMixCurrencies() {
        String idr = open("BANK", AccountType.ASSET);
        String usd = open("BANK", AccountType.ASSET, "USD", false);
        assertEquals(
                ErrorCode.CURRENCY_MISMATCH, refusal(() -> post(key(), "Fx", Posting.debit(idr, 5), Posting.credit(usd, 5))));
        assertEquals(0, ledger.account(idr).balance());
        assertEquals(0, ledger.account(usd).balance());
    }

    // ---- overdrafts -----------------------------------------------------------------------

    @Test
    void anAssetCannotGoBelowZero() {
        String bank = open("BANK", AccountType.ASSET);
        String expense = open("EXPENSE", AccountType.EXPENSE);
        ErrorCode code = refusal(() -> post(key(), "Spend", Posting.credit(bank, 1), Posting.debit(expense, 1)));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, code);
        assertEquals(0, ledger.account(bank).balance());
        assertEquals(0, ledger.account(expense).balance());
    }

    @Test
    void aLiabilityCannotGoBelowZeroEither() {
        String bank = open("BANK", AccountType.ASSET);
        String deferred = open("DEFERRED", AccountType.LIABILITY);
        String revenue = open("REVENUE", AccountType.REVENUE);
        post(key(), "Sale", Posting.debit(bank, 100), Posting.credit(deferred, 100));
        post(key(), "Recognise", Posting.debit(deferred, 100), Posting.credit(revenue, 100));
        assertEquals(
                ErrorCode.INSUFFICIENT_FUNDS,
                refusal(() -> post(key(), "Too much", Posting.debit(deferred, 1), Posting.credit(revenue, 1))));
        assertEquals(0, natural(deferred));
    }

    @Test
    void anAccountThatAllowsNegativeMayGoBelowZero() {
        String overdraft = open("OD", AccountType.ASSET, "IDR", true);
        String expense = open("EXPENSE", AccountType.EXPENSE);
        post(key(), "Spend", Posting.credit(overdraft, 40), Posting.debit(expense, 40));
        assertEquals(-40, natural(overdraft));
    }

    @Test
    void onlyWhereAnAccountEndsUpMatters() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        String expense = open("EXPENSE", AccountType.EXPENSE);
        fund(bank, equity, 50);

        // The bank dips to -30 after the first line and ends at 10.
        post(key(), "Net", Posting.credit(bank, 80), Posting.debit(bank, 40), Posting.debit(expense, 40));

        assertEquals(10, natural(bank));
    }

    // ---- idempotency ----------------------------------------------------------------------

    @Test
    void repeatingARequestReturnsTheSameTransactionAndChangesNothing() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        String key = key();

        PostResult first = post(key, "Fund", Posting.debit(bank, 500), Posting.credit(equity, 500));
        clock.advance(Duration.ofMinutes(5));
        PostResult again = post(key, "Fund", Posting.debit(bank, 500), Posting.credit(equity, 500));

        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(first.transaction(), again.transaction());
        assertEquals(500, natural(bank));
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        String key = key();
        post(key, "Fund", Posting.debit(bank, 500), Posting.credit(equity, 500));

        assertEquals(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                refusal(() -> post(key, "Fund", Posting.debit(bank, 501), Posting.credit(equity, 501))));
        assertEquals(500, natural(bank));
    }

    @Test
    void aRefusedRequestDoesNotUseUpItsKey() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        String expense = open("EXPENSE", AccountType.EXPENSE);
        String key = key();

        assertEquals(
                ErrorCode.INSUFFICIENT_FUNDS,
                refusal(() -> post(key, "Spend", Posting.credit(bank, 100), Posting.debit(expense, 100))));
        fund(bank, equity, 100);
        PostResult retry = post(key, "Spend", Posting.credit(bank, 100), Posting.debit(expense, 100));

        assertFalse(retry.replayed());
        assertEquals(0, natural(bank));
    }

    // ---- reversal -------------------------------------------------------------------------

    @Test
    void aReversalPostsTheOppositeAndRestoresTheBalances() {
        String bank = open("BANK", AccountType.ASSET);
        String deferred = open("DEFERRED", AccountType.LIABILITY);
        LedgerTransaction sale = post(key(), "Sale", Posting.debit(bank, 99_000), Posting.credit(deferred, 99_000)).transaction();

        PostResult reversal = ledger.reverse(new ReversalRequest(sale.id(), key(), "Refund within 14 days"));

        assertFalse(reversal.replayed());
        assertEquals(sale.id(), reversal.transaction().reversalOf());
        assertEquals("Refund within 14 days", reversal.transaction().reason());
        assertEquals(Side.CREDIT, reversal.transaction().lines().get(0).side());
        assertEquals(0, natural(bank));
        assertEquals(0, natural(deferred));
        assertEquals(sale, ledger.transaction(sale.id()));
    }

    @Test
    void aTransactionCanBeReversedOnlyOnce() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        LedgerTransaction tx = post(key(), "Fund", Posting.debit(bank, 10), Posting.credit(equity, 10)).transaction();
        ledger.reverse(new ReversalRequest(tx.id(), key(), "first"));

        assertEquals(ErrorCode.ALREADY_REVERSED, refusal(() -> ledger.reverse(new ReversalRequest(tx.id(), key(), "second"))));
        assertEquals(0, natural(bank));
    }

    @Test
    void retryingAReversalWithTheSameKeyIsAReplay() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        LedgerTransaction tx = post(key(), "Fund", Posting.debit(bank, 10), Posting.credit(equity, 10)).transaction();
        String key = key();

        PostResult first = ledger.reverse(new ReversalRequest(tx.id(), key, "oops"));
        PostResult again = ledger.reverse(new ReversalRequest(tx.id(), key, "oops"));

        assertTrue(again.replayed());
        assertEquals(first.transaction(), again.transaction());
    }

    @Test
    void aReversalCannotBeReversed() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        LedgerTransaction tx = post(key(), "Fund", Posting.debit(bank, 10), Posting.credit(equity, 10)).transaction();
        LedgerTransaction reversal = ledger.reverse(new ReversalRequest(tx.id(), key(), "oops")).transaction();

        assertEquals(ErrorCode.NOT_REVERSIBLE, refusal(() -> ledger.reverse(new ReversalRequest(reversal.id(), key(), "again"))));
    }

    @Test
    void anUnknownTransactionCannotBeReversed() {
        assertEquals(
                ErrorCode.TRANSACTION_NOT_FOUND,
                refusal(() -> ledger.reverse(new ReversalRequest(UUID.randomUUID(), key(), "nothing"))));
    }

    @Test
    void aReversalThatWouldOverdrawAnAccountIsRefused() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        String expense = open("EXPENSE", AccountType.EXPENSE);
        LedgerTransaction funding = post(key(), "Fund", Posting.debit(bank, 100), Posting.credit(equity, 100)).transaction();
        post(key(), "Spend", Posting.credit(bank, 100), Posting.debit(expense, 100));

        assertEquals(
                ErrorCode.INSUFFICIENT_FUNDS, refusal(() -> ledger.reverse(new ReversalRequest(funding.id(), key(), "undo"))));
        assertEquals(0, natural(bank));
    }

    @Test
    void reusingAKeyForADifferentReversalIsRefused() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        LedgerTransaction a = post(key(), "A", Posting.debit(bank, 10), Posting.credit(equity, 10)).transaction();
        LedgerTransaction b = post(key(), "B", Posting.debit(bank, 10), Posting.credit(equity, 10)).transaction();
        String key = key();
        ledger.reverse(new ReversalRequest(a.id(), key, "x"));

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, refusal(() -> ledger.reverse(new ReversalRequest(b.id(), key, "x"))));
    }

    // ---- reading --------------------------------------------------------------------------

    @Test
    void aStatementListsMovementsOldestFirstWithRunningBalances() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        fund(bank, equity, 100);
        fund(bank, equity, 50);
        post(key(), "Out", Posting.credit(bank, 30), Posting.debit(open("EXPENSE", AccountType.EXPENSE), 30));

        List<StatementLine> lines = ledger.statement(bank, 0, 10);

        assertEquals(3, lines.size());
        assertEquals(List.of(100L, 150L, 120L), lines.stream().map(StatementLine::balanceAfter).toList());
        assertEquals(Side.CREDIT, lines.get(2).side());
        assertEquals("Out", lines.get(2).description());
        assertTrue(lines.get(0).entryId() < lines.get(1).entryId());
    }

    @Test
    void aStatementCanBeReadInPages() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        for (int i = 0; i < 5; i++) {
            fund(bank, equity, 10);
        }

        List<StatementLine> first = ledger.statement(bank, 0, 2);
        List<StatementLine> second = ledger.statement(bank, first.get(1).entryId(), 2);
        List<StatementLine> third = ledger.statement(bank, second.get(1).entryId(), 2);

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertEquals(1, third.size());
        assertEquals(50, third.get(0).balanceAfter());
        assertTrue(ledger.statement(bank, third.get(0).entryId(), 2).isEmpty());
    }

    @Test
    void aStatementRejectsBadPagingAndUnknownAccounts() {
        String bank = open("BANK", AccountType.ASSET);
        assertEquals(ErrorCode.INVALID_REQUEST, refusal(() -> ledger.statement(bank, 0, 0)));
        assertEquals(ErrorCode.INVALID_REQUEST, refusal(() -> ledger.statement(bank, 0, LedgerService.MAX_STATEMENT_LIMIT + 1)));
        assertEquals(ErrorCode.INVALID_REQUEST, refusal(() -> ledger.statement(bank, -1, 5)));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, refusal(() -> ledger.statement("NO-SUCH-ACCOUNT", 0, 5)));
    }

    @Test
    void aBalanceCanBeReadAsOfAMomentInThePast() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        fund(bank, equity, 100);
        Instant afterFirst = clock.instant();
        clock.advance(Duration.ofHours(1));
        fund(bank, equity, 25);
        Instant afterSecond = clock.instant();

        assertEquals(0, ledger.balanceAsOf(bank, START.minusSeconds(1)).balance());
        assertEquals(100, ledger.balanceAsOf(bank, afterFirst).balance());
        BalanceAt latest = ledger.balanceAsOf(bank, afterSecond);
        assertEquals(125, latest.balance());
        assertEquals("IDR", latest.currency());
        assertEquals(afterSecond, latest.asOf());
    }

    @Test
    void aBalanceAsOfIsReadInTheAccountsOwnDirection() {
        String bank = open("BANK", AccountType.ASSET);
        String deferred = open("DEFERRED", AccountType.LIABILITY);
        post(key(), "Sale", Posting.debit(bank, 70), Posting.credit(deferred, 70));
        assertEquals(70, ledger.balanceAsOf(deferred, clock.instant()).balance());
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, refusal(() -> ledger.balanceAsOf("NO-SUCH-ACCOUNT", clock.instant())));
    }

    @Test
    void theTrialBalanceAlwaysBalances() {
        String bank = open("BANK", AccountType.ASSET);
        String deferred = open("DEFERRED", AccountType.LIABILITY);
        String usd = open("USD", AccountType.ASSET, "USD", false);
        String usdEquity = open("USDEQ", AccountType.EQUITY, "USD", false);
        post(key(), "Sale", Posting.debit(bank, 189_000), Posting.credit(deferred, 189_000));
        post(key(), "Dollars", Posting.debit(usd, 12), Posting.credit(usdEquity, 12));

        TrialBalance trial = ledger.trialBalance();

        assertTrue(trial.balanced());
        assertTrue(trial.currencies().stream().anyMatch(c -> c.currency().equals("IDR") && c.totalDebits() >= 189_000));
        assertTrue(trial.currencies().stream().anyMatch(c -> c.currency().equals("USD")));
    }

    @Test
    void theIntegrityCheckFindsNothingWrongInAHealthyLedger() {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        fund(bank, equity, 40);
        LedgerTransaction tx = post(key(), "Fund", Posting.debit(bank, 5), Posting.credit(equity, 5)).transaction();
        ledger.reverse(new ReversalRequest(tx.id(), key(), "undo"));

        IntegrityReport report = ledger.integrity();

        assertTrue(report.healthy());
        assertTrue(report.discrepancies().isEmpty());
        assertTrue(report.accountsChecked() >= 2);
    }

    // ---- concurrency ----------------------------------------------------------------------

    @Test
    void manyTransfersAtOnceNeverLoseOrCreateMoney() throws Exception {
        String equity = open("EQUITY", AccountType.EQUITY);
        List<String> wallets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String wallet = open("WALLET", AccountType.ASSET);
            fund(wallet, equity, 10_000);
            wallets.add(wallet);
        }
        int workers = 8;
        int perWorker = 40;

        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            int seed = w;
            jobs.add(() -> {
                int refused = 0;
                for (int i = 0; i < perWorker; i++) {
                    String from = wallets.get((seed + i) % wallets.size());
                    String to = wallets.get((seed + i + 1 + (i % 3)) % wallets.size());
                    if (from.equals(to)) {
                        continue;
                    }
                    try {
                        post(key(), "Transfer", Posting.credit(from, 700), Posting.debit(to, 700));
                    } catch (LedgerException e) {
                        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e.code());
                        refused++;
                    }
                }
                return refused;
            });
        }
        runAll(jobs);

        long total = 0;
        for (String wallet : wallets) {
            long balance = natural(wallet);
            assertTrue(balance >= 0, "wallet went negative: " + balance);
            total += balance;
        }
        assertEquals(40_000, total);
        assertTrue(ledger.integrity().healthy());
    }

    @Test
    void theSameRequestSentAtOnceIsRecordedOnce() throws Exception {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        String key = key();
        List<Callable<PostResult>> jobs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            jobs.add(() -> post(key, "Fund", Posting.debit(bank, 250), Posting.credit(equity, 250)));
        }

        List<PostResult> results = runAll(jobs);

        assertEquals(1, results.stream().filter(r -> !r.replayed()).count());
        assertEquals(1, results.stream().map(r -> r.transaction().id()).distinct().count());
        assertEquals(250, natural(bank));
    }

    @Test
    void theSameTransactionReversedAtOnceIsReversedOnce() throws Exception {
        String bank = open("BANK", AccountType.ASSET);
        String equity = open("EQUITY", AccountType.EQUITY);
        LedgerTransaction tx = post(key(), "Fund", Posting.debit(bank, 90), Posting.credit(equity, 90)).transaction();
        List<Callable<String>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            jobs.add(() -> {
                try {
                    ledger.reverse(new ReversalRequest(tx.id(), key(), "race"));
                    return "ok";
                } catch (LedgerException e) {
                    return e.code().name();
                }
            });
        }

        List<String> outcomes = runAll(jobs);

        assertEquals(1, outcomes.stream().filter("ok"::equals).count());
        assertEquals(7, outcomes.stream().filter("ALREADY_REVERSED"::equals).count());
        assertEquals(0, natural(bank));
    }

    private static <T> List<T> runAll(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        try {
            List<Future<T>> futures = pool.invokeAll(jobs);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theTestHarnessHasAClock() {
        assertNotNull(clock);
    }
}
