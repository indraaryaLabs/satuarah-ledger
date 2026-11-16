package click.satuarah.ledger.domain;

import java.util.UUID;

/** A request to undo a transaction by posting its opposite. */
public record ReversalRequest(UUID transactionId, String idempotencyKey, String reason) {

    public ReversalRequest {
        if (transactionId == null) {
            throw LedgerException.invalid("transaction id is required");
        }
        NewTransaction.requireKey(idempotencyKey);
        if (reason == null || reason.isBlank() || reason.length() > 300) {
            throw LedgerException.invalid("reason must be 1 to 300 characters");
        }
    }

    public String fingerprint() {
        return NewTransaction.sha256("reversal\n" + transactionId + "\n" + reason + "\n");
    }
}
