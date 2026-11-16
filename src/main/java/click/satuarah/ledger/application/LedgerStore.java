package click.satuarah.ledger.application;

import click.satuarah.ledger.domain.Account;
import click.satuarah.ledger.domain.LedgerTransaction;
import click.satuarah.ledger.domain.StatementLine;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Where the ledger is kept. The rules live in {@link LedgerService}; a store only keeps and
 * returns data, atomically. Every implementation must pass the shared contract test.
 */
public interface LedgerStore {

    /** Runs {@code work} as one atomic unit: everything it does commits together or not at all. */
    <T> T write(Function<Tx, T> work);

    /** Runs {@code work} against one consistent snapshot. It must not write. */
    <T> T read(Function<Tx, T> work);

    /** The operations available inside {@link #write} and {@link #read}. */
    interface Tx {

        /** Fails with ACCOUNT_EXISTS if the code is taken. */
        void insertAccount(Account account);

        Optional<Account> findAccount(String code);

        /** All accounts, ordered by code. */
        List<Account> allAccounts();

        /**
         * Locks the named accounts so that no other writer can change them until this unit ends, and
         * returns those that exist. Locks are taken in code order, whatever order is asked for, so
         * two writers touching the same accounts can never wait on each other in a circle.
         */
        Map<String, Account> lockAccounts(Collection<String> codes);

        Optional<StoredTransaction> findByIdempotencyKey(String key);

        Optional<LedgerTransaction> findTransaction(UUID id);

        /** The id of the transaction that reverses {@code id}, if there is one. */
        Optional<UUID> findReversalOf(UUID id);

        /**
         * Writes the transaction and its lines, assigns the next sequence number, and sets each
         * touched account's balance to the {@code balanceAfter} of its last line.
         *
         * @throws DuplicateIdempotencyKeyException if the key is already taken
         */
        LedgerTransaction append(TransactionRecord record);

        /** Movements on an account with entry id above {@code afterEntryId}, oldest first. */
        List<StatementLine> statement(String account, long afterEntryId, int limit);

        /** Debits minus credits of the entries committed at or before {@code asOf}. */
        long balanceAsOf(String account, Instant asOf);

        /** For every account with entries, debits minus credits added up from the entries alone. */
        Map<String, Long> entryTotals();
    }

    /** A transaction as stored, with the fingerprint of the request that created it. */
    record StoredTransaction(LedgerTransaction transaction, String fingerprint) {}

    /** Everything a store needs to write one transaction. */
    record TransactionRecord(
            UUID id,
            String idempotencyKey,
            String fingerprint,
            String description,
            String currency,
            Instant occurredAt,
            Instant committedAt,
            UUID reversalOf,
            String reason,
            List<LedgerTransaction.Line> lines) {

        public TransactionRecord {
            lines = List.copyOf(lines);
        }
    }
}
