package click.satuarah.ledger.store;

import click.satuarah.ledger.application.DuplicateIdempotencyKeyException;
import click.satuarah.ledger.application.LedgerStore;
import click.satuarah.ledger.domain.Account;
import click.satuarah.ledger.domain.AccountType;
import click.satuarah.ledger.domain.ErrorCode;
import click.satuarah.ledger.domain.LedgerException;
import click.satuarah.ledger.domain.LedgerTransaction;
import click.satuarah.ledger.domain.Side;
import click.satuarah.ledger.domain.StatementLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger in PostgreSQL. Writers take row locks on the accounts they touch, in code order, so
 * they queue instead of deadlocking; readers use one repeatable-read snapshot, so a report never
 * sees half of a transaction.
 */
public final class PostgresLedgerStore implements LedgerStore {

    private static final String ACCOUNT_COLUMNS = "code, name, type, currency, allow_negative, balance, created_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public PostgresLedgerStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.writes = new TransactionTemplate(transactions);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public <T> T write(Function<Tx, T> work) {
        return writes.execute(status -> work.apply(new PgTx()));
    }

    @Override
    public <T> T read(Function<Tx, T> work) {
        return reads.execute(status -> work.apply(new PgTx()));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Account account(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getString("code"),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                rs.getString("currency"),
                rs.getBoolean("allow_negative"),
                rs.getLong("balance"),
                rs.getTimestamp("created_at").toInstant());
    }

    private record Head(
            UUID id,
            long sequence,
            String key,
            String fingerprint,
            String description,
            String currency,
            Instant occurredAt,
            Instant committedAt,
            UUID reversalOf,
            String reason) {}

    private static Head head(ResultSet rs, int row) throws SQLException {
        return new Head(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getString("idempotency_key"),
                rs.getString("fingerprint"),
                rs.getString("description"),
                rs.getString("currency"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("committed_at").toInstant(),
                rs.getObject("reversal_of", UUID.class),
                rs.getString("reason"));
    }

    private final class PgTx implements Tx {

        @Override
        public void insertAccount(Account a) {
            try {
                jdbc.sql("insert into account (" + ACCOUNT_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?)")
                        .params(
                                a.code(),
                                a.name(),
                                a.type().name(),
                                a.currency(),
                                a.allowNegative(),
                                a.balance(),
                                timestamp(a.createdAt()))
                        .update();
            } catch (DuplicateKeyException e) {
                throw new LedgerException(ErrorCode.ACCOUNT_EXISTS, "account " + a.code() + " already exists");
            }
        }

        @Override
        public Optional<Account> findAccount(String code) {
            return jdbc.sql("select " + ACCOUNT_COLUMNS + " from account where code = ?")
                    .param(code)
                    .query(PostgresLedgerStore::account)
                    .optional();
        }

        @Override
        public List<Account> allAccounts() {
            return jdbc.sql("select " + ACCOUNT_COLUMNS + " from account order by code")
                    .query(PostgresLedgerStore::account)
                    .list();
        }

        @Override
        public Map<String, Account> lockAccounts(Collection<String> codes) {
            if (codes.isEmpty()) {
                return Map.of();
            }
            List<String> ordered = new ArrayList<>(new TreeSet<>(codes));
            List<Account> locked = jdbc.sql("select " + ACCOUNT_COLUMNS
                            + " from account where code in (:codes) order by code for update")
                    .param("codes", ordered)
                    .query(PostgresLedgerStore::account)
                    .list();
            Map<String, Account> byCode = new TreeMap<>();
            locked.forEach(a -> byCode.put(a.code(), a));
            return byCode;
        }

        @Override
        public Optional<StoredTransaction> findByIdempotencyKey(String key) {
            return jdbc.sql("select id, seq, idempotency_key, fingerprint, description, currency,"
                            + " occurred_at, committed_at, reversal_of, reason"
                            + " from ledger_transaction where idempotency_key = ?")
                    .param(key)
                    .query(PostgresLedgerStore::head)
                    .optional()
                    .map(h -> new StoredTransaction(assemble(h), h.fingerprint()));
        }

        @Override
        public Optional<LedgerTransaction> findTransaction(UUID id) {
            return jdbc.sql("select id, seq, idempotency_key, fingerprint, description, currency,"
                            + " occurred_at, committed_at, reversal_of, reason"
                            + " from ledger_transaction where id = ?")
                    .param(id)
                    .query(PostgresLedgerStore::head)
                    .optional()
                    .map(this::assemble);
        }

        private LedgerTransaction assemble(Head h) {
            List<LedgerTransaction.Line> lines = jdbc.sql("select line_no, account_code, side, amount, balance_after"
                            + " from ledger_entry where transaction_id = ? order by line_no")
                    .param(h.id())
                    .query((rs, row) -> new LedgerTransaction.Line(
                            rs.getInt("line_no"),
                            rs.getString("account_code"),
                            Side.valueOf(rs.getString("side")),
                            rs.getLong("amount"),
                            rs.getLong("balance_after")))
                    .list();
            return new LedgerTransaction(
                    h.id(),
                    h.sequence(),
                    h.key(),
                    h.description(),
                    h.currency(),
                    h.occurredAt(),
                    h.committedAt(),
                    h.reversalOf(),
                    h.reason(),
                    lines);
        }

        @Override
        public Optional<UUID> findReversalOf(UUID id) {
            return jdbc.sql("select id from ledger_transaction where reversal_of = ?")
                    .param(id)
                    .query((rs, row) -> rs.getObject("id", UUID.class))
                    .optional();
        }

        @Override
        public LedgerTransaction append(TransactionRecord r) {
            long sequence;
            try {
                sequence = jdbc.sql("insert into ledger_transaction (id, idempotency_key, fingerprint, description,"
                                + " currency, occurred_at, committed_at, reversal_of, reason)"
                                + " values (?, ?, ?, ?, ?, ?, ?, cast(? as uuid), cast(? as text)) returning seq")
                        .params(
                                r.id(),
                                r.idempotencyKey(),
                                r.fingerprint(),
                                r.description(),
                                r.currency(),
                                timestamp(r.occurredAt()),
                                timestamp(r.committedAt()),
                                r.reversalOf(),
                                r.reason())
                        .query(Long.class)
                        .single();
            } catch (DuplicateKeyException e) {
                String detail = String.valueOf(e.getMostSpecificCause().getMessage());
                if (detail.contains("uq_transaction_reversal_of")) {
                    throw new LedgerException(
                            ErrorCode.ALREADY_REVERSED, "transaction " + r.reversalOf() + " has already been reversed");
                }
                throw new DuplicateIdempotencyKeyException(r.idempotencyKey(), e);
            }
            Map<String, Long> last = new LinkedHashMap<>();
            for (LedgerTransaction.Line line : r.lines()) {
                jdbc.sql("insert into ledger_entry (transaction_id, line_no, account_code, side, amount, balance_after)"
                                + " values (?, ?, ?, ?, ?, ?)")
                        .params(r.id(), line.lineNo(), line.account(), line.side().name(), line.amount(), line.balanceAfter())
                        .update();
                last.put(line.account(), line.balanceAfter());
            }
            last.forEach((code, balance) ->
                    jdbc.sql("update account set balance = ? where code = ?").params(balance, code).update());
            return new LedgerTransaction(
                    r.id(),
                    sequence,
                    r.idempotencyKey(),
                    r.description(),
                    r.currency(),
                    r.occurredAt(),
                    r.committedAt(),
                    r.reversalOf(),
                    r.reason(),
                    r.lines());
        }

        @Override
        public List<StatementLine> statement(String account, long afterEntryId, int limit) {
            return jdbc.sql("select e.id as entry_id, t.id as transaction_id, t.committed_at, t.description,"
                            + " e.side, e.amount, e.balance_after"
                            + " from ledger_entry e join ledger_transaction t on t.id = e.transaction_id"
                            + " where e.account_code = ? and e.id > ? order by e.id limit ?")
                    .params(account, afterEntryId, limit)
                    .query((rs, row) -> new StatementLine(
                            rs.getLong("entry_id"),
                            rs.getObject("transaction_id", UUID.class),
                            rs.getTimestamp("committed_at").toInstant(),
                            rs.getString("description"),
                            Side.valueOf(rs.getString("side")),
                            rs.getLong("amount"),
                            rs.getLong("balance_after")))
                    .list();
        }

        @Override
        public long balanceAsOf(String account, Instant asOf) {
            return jdbc.sql("select coalesce(sum(case e.side when 'DEBIT' then e.amount else -e.amount end), 0)"
                            + " from ledger_entry e join ledger_transaction t on t.id = e.transaction_id"
                            + " where e.account_code = ? and t.committed_at <= ?")
                    .params(account, timestamp(asOf))
                    .query(Long.class)
                    .single();
        }

        @Override
        public Map<String, Long> entryTotals() {
            Map<String, Long> totals = new TreeMap<>();
            jdbc.sql("select account_code, sum(case side when 'DEBIT' then amount else -amount end) as total"
                            + " from ledger_entry group by account_code")
                    .query((rs, row) -> Map.entry(rs.getString("account_code"), rs.getLong("total")))
                    .list()
                    .forEach(e -> totals.put(e.getKey(), e.getValue()));
            return totals;
        }
    }
}
