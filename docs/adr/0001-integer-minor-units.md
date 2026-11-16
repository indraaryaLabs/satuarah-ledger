# 0001. Amounts are whole numbers of minor units

**Status:** accepted

## Context

A ledger adds and compares amounts millions of times. Binary floating point cannot represent 0.1 exactly, so sums drift, and "debits equal credits" stops being something you can test with `==`.

## Decision

Every amount is a `long` count of the currency's smallest unit, in the domain, in the database (`bigint`) and in the API. Rp 189.000 is `189000`; USD 19.99 is `1999`. The API refuses a fractional or string amount instead of rounding it. A single posting is capped at 10^15, so a transaction of up to 100 postings cannot overflow a `long`; a running account balance is also checked with exact arithmetic.

## Alternatives

- **`BigDecimal` / `numeric`.** Exact, but it carries a scale that every comparison and sum has to agree on, and it invites rounding decisions at the edges. Integers make the rounding decision the caller's, made once, before the money enters the ledger.
- **Decimal strings in JSON.** Safer than floats, but then the server has to parse and validate scale per currency. Not needed when callers already know the minor unit.

## Consequences

The ledger does not know how many decimals a currency has; callers do. Currencies with different exponents (IDR 0 or 2, JPY 0, KWD 3) are handled by the caller choosing the unit and sticking to it per account.
