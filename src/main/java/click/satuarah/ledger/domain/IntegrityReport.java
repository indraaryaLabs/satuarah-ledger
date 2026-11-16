package click.satuarah.ledger.domain;

import java.util.List;

/**
 * Compares what each account says its balance is with what its entries add up to. Anything in
 * {@code discrepancies} means the stored balance has drifted from the history and needs a person.
 */
public record IntegrityReport(int accountsChecked, boolean trialBalanced, List<Discrepancy> discrepancies) {

    public IntegrityReport {
        discrepancies = List.copyOf(discrepancies);
    }

    public boolean healthy() {
        return trialBalanced && discrepancies.isEmpty();
    }

    public record Discrepancy(String account, long storedBalance, long entriesTotal) {}
}
