package click.satuarah.ledger.domain;

import java.time.Instant;

/** An account's balance as the books stood at a moment, in the account's own direction. */
public record BalanceAt(String account, String currency, Instant asOf, long balance) {}
