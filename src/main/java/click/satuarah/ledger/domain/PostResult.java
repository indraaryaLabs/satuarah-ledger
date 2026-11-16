package click.satuarah.ledger.domain;

/** The outcome of posting: the transaction, and whether it already existed for this key. */
public record PostResult(LedgerTransaction transaction, boolean replayed) {}
