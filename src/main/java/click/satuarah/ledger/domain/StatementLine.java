package click.satuarah.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** One movement on one account, with the raw (debits minus credits) balance after it. */
public record StatementLine(
        long entryId,
        UUID transactionId,
        Instant committedAt,
        String description,
        Side side,
        long amount,
        long balanceAfter) {}
