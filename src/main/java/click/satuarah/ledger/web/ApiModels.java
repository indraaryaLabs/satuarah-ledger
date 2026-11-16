package click.satuarah.ledger.web;

import click.satuarah.ledger.domain.Account;
import click.satuarah.ledger.domain.BalanceAt;
import click.satuarah.ledger.domain.IntegrityReport;
import click.satuarah.ledger.domain.LedgerTransaction;
import click.satuarah.ledger.domain.StatementLine;
import click.satuarah.ledger.domain.TrialBalance;
import java.util.List;

/** The JSON shapes of the API. Times are ISO-8601 strings and amounts are whole minor units. */
final class ApiModels {

    private ApiModels() {}

    // ---- requests -------------------------------------------------------------------------

    record OpenAccountRequest(String code, String name, String type, String currency, Boolean allowNegative) {}

    /** {@code amount} is read as a plain object so a fractional number can be refused, not truncated. */
    record PostingRequest(String account, String side, Object amount) {}

    record PostTransactionRequest(String description, String occurredAt, List<PostingRequest> postings) {}

    record ReverseRequestBody(String reason) {}

    // ---- responses ------------------------------------------------------------------------

    record AccountResponse(
            String code,
            String name,
            String type,
            String normalSide,
            String currency,
            boolean allowNegative,
            long balance,
            String createdAt) {

        static AccountResponse of(Account a) {
            return new AccountResponse(
                    a.code(),
                    a.name(),
                    a.type().name(),
                    a.type().normalSide().name(),
                    a.currency(),
                    a.allowNegative(),
                    a.naturalBalance(),
                    a.createdAt().toString());
        }
    }

    record LineResponse(int lineNo, String account, String side, long amount) {}

    record TransactionResponse(
            String id,
            long sequence,
            String description,
            String currency,
            String occurredAt,
            String committedAt,
            String reversalOf,
            String reason,
            List<LineResponse> lines) {

        static TransactionResponse of(LedgerTransaction t) {
            return new TransactionResponse(
                    t.id().toString(),
                    t.sequence(),
                    t.description(),
                    t.currency(),
                    t.occurredAt().toString(),
                    t.committedAt().toString(),
                    t.reversalOf() == null ? null : t.reversalOf().toString(),
                    t.reason(),
                    t.lines().stream()
                            .map(l -> new LineResponse(l.lineNo(), l.account(), l.side().name(), l.amount()))
                            .toList());
        }
    }

    record StatementLineResponse(
            long entryId,
            String transactionId,
            String committedAt,
            String description,
            String side,
            long amount,
            long balanceAfter) {

        static StatementLineResponse of(StatementLine l, Account account) {
            return new StatementLineResponse(
                    l.entryId(),
                    l.transactionId().toString(),
                    l.committedAt().toString(),
                    l.description(),
                    l.side().name(),
                    l.amount(),
                    account.withBalance(l.balanceAfter()).naturalBalance());
        }
    }

    /** {@code nextAfter} is the cursor for the next page, or null when this was the last one. */
    record StatementResponse(String account, String currency, List<StatementLineResponse> lines, Long nextAfter) {}

    record BalanceResponse(String account, String currency, String asOf, long balance) {

        static BalanceResponse of(BalanceAt b) {
            return new BalanceResponse(b.account(), b.currency(), b.asOf().toString(), b.balance());
        }
    }

    record CurrencyTotalsResponse(String currency, long totalDebits, long totalCredits, boolean balanced) {}

    record TrialBalanceResponse(boolean balanced, List<CurrencyTotalsResponse> currencies) {

        static TrialBalanceResponse of(TrialBalance t) {
            return new TrialBalanceResponse(
                    t.balanced(),
                    t.currencies().stream()
                            .map(c -> new CurrencyTotalsResponse(
                                    c.currency(), c.totalDebits(), c.totalCredits(), c.balanced()))
                            .toList());
        }
    }

    record DiscrepancyResponse(String account, long storedBalance, long entriesTotal) {}

    record IntegrityResponse(
            boolean healthy, boolean trialBalanced, int accountsChecked, List<DiscrepancyResponse> discrepancies) {

        static IntegrityResponse of(IntegrityReport r) {
            return new IntegrityResponse(
                    r.healthy(),
                    r.trialBalanced(),
                    r.accountsChecked(),
                    r.discrepancies().stream()
                            .map(d -> new DiscrepancyResponse(d.account(), d.storedBalance(), d.entriesTotal()))
                            .toList());
        }
    }
}
