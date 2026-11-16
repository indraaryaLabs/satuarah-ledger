package click.satuarah.ledger.domain;

/** Stable, machine-readable reasons a request is refused. They are part of the public API. */
public enum ErrorCode {
    INVALID_REQUEST,
    UNBALANCED,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_EXISTS,
    CURRENCY_MISMATCH,
    INSUFFICIENT_FUNDS,
    IDEMPOTENCY_CONFLICT,
    TRANSACTION_NOT_FOUND,
    ALREADY_REVERSED,
    NOT_REVERSIBLE
}
