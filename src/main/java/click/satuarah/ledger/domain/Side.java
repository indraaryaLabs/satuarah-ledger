package click.satuarah.ledger.domain;

/** Which side of the ledger a posting is on. */
public enum Side {
    DEBIT,
    CREDIT;

    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    /** The signed effect of an amount on a balance stored as debits minus credits. */
    public long signed(long amount) {
        return this == DEBIT ? amount : -amount;
    }
}
