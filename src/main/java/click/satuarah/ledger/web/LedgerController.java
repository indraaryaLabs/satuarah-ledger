package click.satuarah.ledger.web;

import click.satuarah.ledger.application.LedgerService;
import click.satuarah.ledger.domain.Account;
import click.satuarah.ledger.domain.PostResult;
import click.satuarah.ledger.domain.StatementLine;
import click.satuarah.ledger.web.ApiModels.AccountResponse;
import click.satuarah.ledger.web.ApiModels.BalanceResponse;
import click.satuarah.ledger.web.ApiModels.IntegrityResponse;
import click.satuarah.ledger.web.ApiModels.OpenAccountRequest;
import click.satuarah.ledger.web.ApiModels.PostTransactionRequest;
import click.satuarah.ledger.web.ApiModels.ReverseRequestBody;
import click.satuarah.ledger.web.ApiModels.StatementLineResponse;
import click.satuarah.ledger.web.ApiModels.StatementResponse;
import click.satuarah.ledger.web.ApiModels.TransactionResponse;
import click.satuarah.ledger.web.ApiModels.TrialBalanceResponse;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The HTTP face of the ledger. It only translates; every rule lives in {@link LedgerService}. */
@RestController
@RequestMapping("/v1")
class LedgerController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";
    private static final int DEFAULT_STATEMENT_LIMIT = 50;

    private final LedgerService ledger;
    private final Clock clock;

    LedgerController(LedgerService ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    // ---- accounts -------------------------------------------------------------------------

    @PostMapping("/accounts")
    ResponseEntity<AccountResponse> openAccount(@RequestBody(required = false) OpenAccountRequest body) {
        Account account = ledger.openAccount(RequestParsing.account(body));
        return ResponseEntity.created(URI.create("/v1/accounts/" + account.code()))
                .body(AccountResponse.of(account));
    }

    @GetMapping("/accounts")
    List<AccountResponse> accounts() {
        return ledger.accounts().stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/accounts/{code}")
    AccountResponse account(@PathVariable String code) {
        return AccountResponse.of(ledger.account(code));
    }

    @GetMapping("/accounts/{code}/statement")
    StatementResponse statement(
            @PathVariable String code,
            @RequestParam(name = "after", defaultValue = "0") long after,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_STATEMENT_LIMIT) int limit) {
        Account account = ledger.account(code);
        List<StatementLine> lines = ledger.statement(code, after, limit);
        Long next = lines.size() == limit ? lines.get(lines.size() - 1).entryId() : null;
        return new StatementResponse(
                account.code(),
                account.currency(),
                lines.stream().map(l -> StatementLineResponse.of(l, account)).toList(),
                next);
    }

    @GetMapping("/accounts/{code}/balance")
    BalanceResponse balance(
            @PathVariable String code, @RequestParam(name = "asOf", required = false) String asOf) {
        Instant at = RequestParsing.instant(asOf, "asOf");
        return BalanceResponse.of(ledger.balanceAsOf(code, at == null ? clock.instant() : at));
    }

    // ---- transactions ---------------------------------------------------------------------

    @PostMapping("/transactions")
    ResponseEntity<TransactionResponse> post(
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody(required = false) PostTransactionRequest body) {
        PostResult result = ledger.post(RequestParsing.transaction(RequestParsing.requireKey(key), body));
        return respond(result);
    }

    @GetMapping("/transactions/{id}")
    TransactionResponse transaction(@PathVariable String id) {
        return TransactionResponse.of(ledger.transaction(RequestParsing.uuid(id)));
    }

    @PostMapping("/transactions/{id}/reversal")
    ResponseEntity<TransactionResponse> reverse(
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody(required = false) ReverseRequestBody body) {
        PostResult result = ledger.reverse(RequestParsing.reversal(id, RequestParsing.requireKey(key), body));
        return respond(result);
    }

    private static ResponseEntity<TransactionResponse> respond(PostResult result) {
        TransactionResponse body = TransactionResponse.of(result.transaction());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/v1/transactions/" + body.id()))
                .body(body);
    }

    // ---- reports --------------------------------------------------------------------------

    @GetMapping("/reports/trial-balance")
    TrialBalanceResponse trialBalance() {
        return TrialBalanceResponse.of(ledger.trialBalance());
    }

    @GetMapping("/reports/integrity")
    IntegrityResponse integrity() {
        return IntegrityResponse.of(ledger.integrity());
    }
}
