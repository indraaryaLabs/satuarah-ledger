package click.satuarah.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An account and where it stands. {@code balance} is debits minus credits in minor units, so it
 * is positive for a net debit and negative for a net credit whatever the account type is; use
 * {@link #naturalBalance()} for the number a person expects to read.
 */
public record Account(
        String code,
        String name,
        AccountType type,
        String currency,
        boolean allowNegative,
        long balance,
        Instant createdAt) {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$");

    public Account {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static boolean validCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    /** The balance in the account's own direction: a bank account is positive when it holds money. */
    public long naturalBalance() {
        return type.normalSide() == Side.DEBIT ? balance : -balance;
    }

    /** Whether a raw balance would leave the account on the wrong side of zero. */
    public boolean overdrawnAt(long rawBalance) {
        if (allowNegative) {
            return false;
        }
        return type.normalSide() == Side.DEBIT ? rawBalance < 0 : rawBalance > 0;
    }

    public Account withBalance(long newBalance) {
        return new Account(code, name, type, currency, allowNegative, newBalance, createdAt);
    }
}
