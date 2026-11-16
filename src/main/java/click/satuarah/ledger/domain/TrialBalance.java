package click.satuarah.ledger.domain;

import java.util.List;

/**
 * Every account's balance, totalled per currency. In a sound ledger the debit and credit totals
 * are always equal.
 */
public record TrialBalance(List<CurrencyTotals> currencies) {

    public TrialBalance {
        currencies = List.copyOf(currencies);
    }

    public boolean balanced() {
        return currencies.stream().allMatch(CurrencyTotals::balanced);
    }

    public record CurrencyTotals(String currency, long totalDebits, long totalCredits) {
        public boolean balanced() {
            return totalDebits == totalCredits;
        }
    }
}
