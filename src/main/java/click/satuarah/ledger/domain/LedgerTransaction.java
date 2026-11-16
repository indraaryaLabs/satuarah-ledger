package click.satuarah.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A recorded transaction. It never changes after it is written. */
public record LedgerTransaction(
        UUID id,
        long sequence,
        String idempotencyKey,
        String description,
        String currency,
        Instant occurredAt,
        Instant committedAt,
        UUID reversalOf,
        String reason,
        List<Line> lines) {

    public LedgerTransaction {
        lines = List.copyOf(lines);
    }

    /** One posting as it was applied, with the account's balance right after it. */
    public record Line(int lineNo, String account, Side side, long amount, long balanceAfter) {}
}
