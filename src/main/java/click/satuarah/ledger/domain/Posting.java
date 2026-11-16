package click.satuarah.ledger.domain;

import java.util.Objects;

/** One line of a transaction: an amount, in minor units, moved on one side of one account. */
public record Posting(String account, Side side, long amount) {

    /** Large enough for any real amount, small enough that a hundred of them cannot overflow a long. */
    public static final long MAX_AMOUNT = 1_000_000_000_000_000L;

    public Posting {
        if (!Account.validCode(account)) {
            throw LedgerException.invalid("posting account is not a valid account code");
        }
        Objects.requireNonNull(side, "side");
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw LedgerException.invalid("amount must be a whole number of minor units from 1 to " + MAX_AMOUNT);
        }
    }

    public static Posting debit(String account, long amount) {
        return new Posting(account, Side.DEBIT, amount);
    }

    public static Posting credit(String account, long amount) {
        return new Posting(account, Side.CREDIT, amount);
    }
}
