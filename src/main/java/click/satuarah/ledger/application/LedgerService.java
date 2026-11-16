package click.satuarah.ledger.application;

import click.satuarah.ledger.application.LedgerStore.StoredTransaction;
import click.satuarah.ledger.application.LedgerStore.Tx;
import click.satuarah.ledger.domain.Account;
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
import click.satuarah.ledger.domain.StatementLine;
import click.satuarah.ledger.domain.TrialBalance;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The ledger's rules. Every posted transaction is balanced, in one currency, and never leaves an
 * account on the wrong side of zero unless that account allows it. A transaction is recorded once
 * per idempotency key, and is only ever undone by posting its opposite.
 */
public final class LedgerService {

    public static final int MAX_STATEMENT_LIMIT = 200;

    private final LedgerStore store;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public LedgerService(LedgerStore store, Clock clock) {
        this(store, clock, UUID::randomUUID);
    }

    public LedgerService(LedgerStore store, Clock clock, Supplier<UUID> ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    // ---- accounts -------------------------------------------------------------------------

    public Account openAccount(NewAccount request) {
        return store.write(tx -> {
            Account account = new Account(
                    request.code(),
                    request.name(),
                    request.type(),
                    request.currency(),
                    request.allowNegative(),
                    0L,
                    now());
            tx.insertAccount(account);
            return account;
        });
    }

    public Account account(String code) {
        return store.read(tx -> requireAccount(tx, code));
    }

    public List<Account> accounts() {
        return store.read(Tx::allAccounts);
    }

    // ---- posting --------------------------------------------------------------------------

    /** Records a transaction, or returns the one already recorded for the same key and request. */
    public PostResult post(NewTransaction request) {
        return withKeyRaceRetry(tx -> doPost(tx, request));
    }

    /** Undoes a transaction by posting its opposite. A transaction can be reversed once. */
    public PostResult reverse(ReversalRequest request) {
        return withKeyRaceRetry(tx -> doReverse(tx, request));
    }

    public LedgerTransaction transaction(UUID id) {
        return store.read(tx -> tx.findTransaction(id).orElseThrow(() -> transactionNotFound(id)));
    }

    private PostResult doPost(Tx tx, NewTransaction request) {
        String fingerprint = request.fingerprint();
        PostResult earlier = replay(tx, request.idempotencyKey(), fingerprint);
        if (earlier != null) {
            return earlier;
        }
        TreeSet<String> codes = new TreeSet<>();
        request.postings().forEach(p -> codes.add(p.account()));
        Map<String, Account> accounts = lockExisting(tx, codes);
        // A concurrent request with the same key may have committed while this one waited for the locks.
        earlier = replay(tx, request.idempotencyKey(), fingerprint);
        if (earlier != null) {
            return earlier;
        }
        LedgerTransaction written = write(
                tx,
                accounts,
                request.idempotencyKey(),
                fingerprint,
                request.description(),
                request.occurredAt(),
                request.postings(),
                null,
                null);
        return new PostResult(written, false);
    }

    private PostResult doReverse(Tx tx, ReversalRequest request) {
        String fingerprint = request.fingerprint();
        PostResult earlier = replay(tx, request.idempotencyKey(), fingerprint);
        if (earlier != null) {
            return earlier;
        }
        LedgerTransaction original = tx.findTransaction(request.transactionId())
                .orElseThrow(() -> transactionNotFound(request.transactionId()));
        if (original.reversalOf() != null) {
            throw new LedgerException(
                    ErrorCode.NOT_REVERSIBLE,
                    "transaction " + original.id() + " is itself a reversal; post a new transaction instead");
        }
        TreeSet<String> codes = new TreeSet<>();
        original.lines().forEach(l -> codes.add(l.account()));
        Map<String, Account> accounts = lockExisting(tx, codes);
        earlier = replay(tx, request.idempotencyKey(), fingerprint);
        if (earlier != null) {
            return earlier;
        }
        if (tx.findReversalOf(original.id()).isPresent()) {
            throw new LedgerException(
                    ErrorCode.ALREADY_REVERSED, "transaction " + original.id() + " has already been reversed");
        }
        List<Posting> opposite = original.lines().stream()
                .map(l -> new Posting(l.account(), l.side().opposite(), l.amount()))
                .toList();
        LedgerTransaction written = write(
                tx,
                accounts,
                request.idempotencyKey(),
                fingerprint,
                "Reversal of " + original.id() + ": " + request.reason(),
                null,
                opposite,
                original.id(),
                request.reason());
        return new PostResult(written, false);
    }

    private PostResult replay(Tx tx, String key, String fingerprint) {
        StoredTransaction stored = tx.findByIdempotencyKey(key).orElse(null);
        if (stored == null) {
            return null;
        }
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new LedgerException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key " + key + " was already used for a different request");
        }
        return new PostResult(stored.transaction(), true);
    }

    private static Map<String, Account> lockExisting(Tx tx, TreeSet<String> codes) {
        Map<String, Account> accounts = tx.lockAccounts(codes);
        List<String> missing = codes.stream().filter(c -> !accounts.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + String.join(", ", missing));
        }
        return accounts;
    }

    private LedgerTransaction write(
            Tx tx,
            Map<String, Account> accounts,
            String key,
            String fingerprint,
            String description,
            Instant occurredAt,
            List<Posting> postings,
            UUID reversalOf,
            String reason) {
        String currency = singleCurrency(accounts);
        Instant now = now();

        Map<String, Long> running = new HashMap<>();
        accounts.forEach((code, account) -> running.put(code, account.balance()));
        List<LedgerTransaction.Line> lines = new ArrayList<>(postings.size());
        int lineNo = 1;
        for (Posting posting : postings) {
            long after;
            try {
                after = Math.addExact(running.get(posting.account()), posting.side().signed(posting.amount()));
            } catch (ArithmeticException overflow) {
                throw LedgerException.invalid("account " + posting.account() + " balance would exceed the supported range");
            }
            running.put(posting.account(), after);
            lines.add(new LedgerTransaction.Line(lineNo++, posting.account(), posting.side(), posting.amount(), after));
        }
        // Only where an account ends up matters, not where it passes through on the way.
        for (Map.Entry<String, Long> end : new TreeMap<>(running).entrySet()) {
            if (accounts.get(end.getKey()).overdrawnAt(end.getValue())) {
                throw new LedgerException(
                        ErrorCode.INSUFFICIENT_FUNDS,
                        "account " + end.getKey() + " would go below zero");
            }
        }
        return tx.append(new LedgerStore.TransactionRecord(
                ids.get(),
                key,
                fingerprint,
                description,
                currency,
                occurredAt == null ? now : occurredAt.truncatedTo(ChronoUnit.MICROS),
                now,
                reversalOf,
                reason,
                lines));
    }

    /** Time as the database keeps it, so a transaction reads the same when it is replayed later. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String singleCurrency(Map<String, Account> accounts) {
        TreeSet<String> currencies = new TreeSet<>();
        accounts.values().forEach(a -> currencies.add(a.currency()));
        if (currencies.size() != 1) {
            throw new LedgerException(
                    ErrorCode.CURRENCY_MISMATCH,
                    "a transaction must use one currency, but these accounts use " + String.join(", ", currencies));
        }
        return currencies.first();
    }

    private <T> T withKeyRaceRetry(Function<Tx, T> work) {
        try {
            return store.write(work);
        } catch (DuplicateIdempotencyKeyException raced) {
            // Another request with this key committed first. Run again: it will now be found and replayed.
            return store.write(work);
        }
    }

    // ---- reading --------------------------------------------------------------------------

    public List<StatementLine> statement(String code, long afterEntryId, int limit) {
        if (limit < 1 || limit > MAX_STATEMENT_LIMIT) {
            throw LedgerException.invalid("limit must be between 1 and " + MAX_STATEMENT_LIMIT);
        }
        if (afterEntryId < 0) {
            throw LedgerException.invalid("after must not be negative");
        }
        return store.read(tx -> {
            requireAccount(tx, code);
            return tx.statement(code, afterEntryId, limit);
        });
    }

    public BalanceAt balanceAsOf(String code, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return store.read(tx -> {
            Account account = requireAccount(tx, code);
            long raw = tx.balanceAsOf(code, asOf);
            long natural = account.withBalance(raw).naturalBalance();
            return new BalanceAt(code, account.currency(), asOf, natural);
        });
    }

    public TrialBalance trialBalance() {
        return store.read(tx -> trialBalance(tx.allAccounts()));
    }

    public IntegrityReport integrity() {
        return store.read(tx -> {
            List<Account> accounts = tx.allAccounts();
            Map<String, Long> totals = tx.entryTotals();
            List<IntegrityReport.Discrepancy> drift = new ArrayList<>();
            for (Account account : accounts) {
                long fromEntries = totals.getOrDefault(account.code(), 0L);
                if (fromEntries != account.balance()) {
                    drift.add(new IntegrityReport.Discrepancy(account.code(), account.balance(), fromEntries));
                }
            }
            return new IntegrityReport(accounts.size(), trialBalance(accounts).balanced(), drift);
        });
    }

    private static TrialBalance trialBalance(List<Account> accounts) {
        Map<String, long[]> perCurrency = new TreeMap<>();
        for (Account account : accounts) {
            long[] totals = perCurrency.computeIfAbsent(account.currency(), c -> new long[2]);
            if (account.balance() > 0) {
                totals[0] = Math.addExact(totals[0], account.balance());
            } else {
                totals[1] = Math.addExact(totals[1], -account.balance());
            }
        }
        List<TrialBalance.CurrencyTotals> rows = new ArrayList<>();
        perCurrency.forEach((currency, t) -> rows.add(new TrialBalance.CurrencyTotals(currency, t[0], t[1])));
        return new TrialBalance(rows);
    }

    private static Account requireAccount(Tx tx, String code) {
        return tx.findAccount(code)
                .orElseThrow(() -> new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + code));
    }

    private static LedgerException transactionNotFound(UUID id) {
        return new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND, "unknown transaction " + id);
    }
}
