package click.satuarah.ledger.store;

import click.satuarah.ledger.application.DuplicateIdempotencyKeyException;
import click.satuarah.ledger.application.LedgerStore;
import click.satuarah.ledger.domain.Account;
import click.satuarah.ledger.domain.ErrorCode;
import click.satuarah.ledger.domain.LedgerException;
import click.satuarah.ledger.domain.LedgerTransaction;
import click.satuarah.ledger.domain.Side;
import click.satuarah.ledger.domain.StatementLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A single-writer store that keeps everything in memory. It exists so the rules can be tested
 * without a database, and it is held to the same contract as the PostgreSQL store.
 */
public final class InMemoryLedgerStore implements LedgerStore {

    private final ReentrantLock lock = new ReentrantLock();
    private State state = new State();

    @Override
    public <T> T write(Function<Tx, T> work) {
        lock.lock();
        try {
            State before = state.copy();
            try {
                return work.apply(new MemoryTx(state));
            } catch (RuntimeException e) {
                state = before;
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> T read(Function<Tx, T> work) {
        lock.lock();
        try {
            return work.apply(new MemoryTx(state));
        } finally {
            lock.unlock();
        }
    }

    private record Row(
            long entryId,
            UUID transactionId,
            Instant committedAt,
            String description,
            String account,
            Side side,
            long amount,
            long balanceAfter) {}

    private static final class State {
        final Map<String, Account> accounts = new TreeMap<>();
        final Map<UUID, StoredTransaction> transactions = new HashMap<>();
        final Map<String, UUID> byKey = new HashMap<>();
        final Map<UUID, UUID> reversals = new HashMap<>();
        final List<Row> rows = new ArrayList<>();
        long nextSequence = 1;
        long nextEntryId = 1;

        State copy() {
            State copy = new State();
            copy.accounts.putAll(accounts);
            copy.transactions.putAll(transactions);
            copy.byKey.putAll(byKey);
            copy.reversals.putAll(reversals);
            copy.rows.addAll(rows);
            copy.nextSequence = nextSequence;
            copy.nextEntryId = nextEntryId;
            return copy;
        }
    }

    private static final class MemoryTx implements Tx {
        private final State state;

        MemoryTx(State state) {
            this.state = state;
        }

        @Override
        public void insertAccount(Account account) {
            if (state.accounts.containsKey(account.code())) {
                throw new LedgerException(ErrorCode.ACCOUNT_EXISTS, "account " + account.code() + " already exists");
            }
            state.accounts.put(account.code(), account);
        }

        @Override
        public Optional<Account> findAccount(String code) {
            return Optional.ofNullable(state.accounts.get(code));
        }

        @Override
        public List<Account> allAccounts() {
            return new ArrayList<>(state.accounts.values());
        }

        @Override
        public Map<String, Account> lockAccounts(Collection<String> codes) {
            Map<String, Account> found = new TreeMap<>();
            for (String code : codes) {
                Account account = state.accounts.get(code);
                if (account != null) {
                    found.put(code, account);
                }
            }
            return found;
        }

        @Override
        public Optional<StoredTransaction> findByIdempotencyKey(String key) {
            UUID id = state.byKey.get(key);
            return id == null ? Optional.empty() : Optional.of(state.transactions.get(id));
        }

        @Override
        public Optional<LedgerTransaction> findTransaction(UUID id) {
            return Optional.ofNullable(state.transactions.get(id)).map(StoredTransaction::transaction);
        }

        @Override
        public Optional<UUID> findReversalOf(UUID id) {
            return Optional.ofNullable(state.reversals.get(id));
        }

        @Override
        public LedgerTransaction append(TransactionRecord record) {
            if (state.byKey.containsKey(record.idempotencyKey())) {
                throw new DuplicateIdempotencyKeyException(record.idempotencyKey(), null);
            }
            LedgerTransaction transaction = new LedgerTransaction(
                    record.id(),
                    state.nextSequence++,
                    record.idempotencyKey(),
                    record.description(),
                    record.currency(),
                    record.occurredAt(),
                    record.committedAt(),
                    record.reversalOf(),
                    record.reason(),
                    record.lines());
            state.transactions.put(record.id(), new StoredTransaction(transaction, record.fingerprint()));
            state.byKey.put(record.idempotencyKey(), record.id());
            if (record.reversalOf() != null) {
                state.reversals.put(record.reversalOf(), record.id());
            }
            for (LedgerTransaction.Line line : record.lines()) {
                state.rows.add(new Row(
                        state.nextEntryId++,
                        record.id(),
                        record.committedAt(),
                        record.description(),
                        line.account(),
                        line.side(),
                        line.amount(),
                        line.balanceAfter()));
                state.accounts.computeIfPresent(line.account(), (code, account) -> account.withBalance(line.balanceAfter()));
            }
            return transaction;
        }

        @Override
        public List<StatementLine> statement(String account, long afterEntryId, int limit) {
            return state.rows.stream()
                    .filter(r -> r.account().equals(account) && r.entryId() > afterEntryId)
                    .limit(limit)
                    .map(r -> new StatementLine(
                            r.entryId(),
                            r.transactionId(),
                            r.committedAt(),
                            r.description(),
                            r.side(),
                            r.amount(),
                            r.balanceAfter()))
                    .toList();
        }

        @Override
        public long balanceAsOf(String account, Instant asOf) {
            long total = 0;
            for (Row row : state.rows) {
                if (row.account().equals(account) && !row.committedAt().isAfter(asOf)) {
                    total += row.side().signed(row.amount());
                }
            }
            return total;
        }

        @Override
        public Map<String, Long> entryTotals() {
            Map<String, Long> totals = new TreeMap<>();
            for (Row row : state.rows) {
                totals.merge(row.account(), row.side().signed(row.amount()), Long::sum);
            }
            return totals;
        }
    }
}
