package click.satuarah.ledger.application;

/**
 * Thrown by a store when two writers raced to use the same idempotency key. The loser's
 * transaction is gone, so the service runs the request again and finds the winner's result.
 */
public final class DuplicateIdempotencyKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateIdempotencyKeyException(String key, Throwable cause) {
        super("idempotency key already used: " + key, cause);
    }
}
