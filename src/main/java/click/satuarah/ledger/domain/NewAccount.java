package click.satuarah.ledger.domain;

import java.util.Currency;

/** A request to open an account. Opening balances are posted as transactions, never set here. */
public record NewAccount(String code, String name, AccountType type, String currency, boolean allowNegative) {

    public NewAccount {
        if (!Account.validCode(code)) {
            throw LedgerException.invalid(
                    "code must be 1 to 32 letters, digits, dots, dashes or underscores, starting with a letter or digit");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw LedgerException.invalid("name must be 1 to 120 characters");
        }
        if (type == null) {
            throw LedgerException.invalid("type is required");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw LedgerException.invalid("currency must be an upper-case ISO 4217 code such as IDR");
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("unknown currency " + currency);
        }
    }
}
