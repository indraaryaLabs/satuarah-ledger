# 0005. The database enforces the invariants too

**Status:** accepted

## Context

The service checks every rule before writing. But the service is one writer among several possible: a later refactor, a migration script, an engineer with a SQL prompt.

## Decision

The rules that make a ledger a ledger are also enforced in PostgreSQL:

- `ledger_entry` and `ledger_transaction` reject update, delete and truncate (triggers).
- A deferred constraint trigger, checked at commit, requires every transaction to have at least two lines, equal debits and credits, and a single currency. Deferred, so lines can be inserted one at a time.
- `CHECK` constraints on amounts (`> 0`), sides, currency codes, and on an account's balance sign.
- Unique constraints on the idempotency key and on `reversal_of`.

`PostgresInvariantsTest` goes around the application with raw SQL to prove each one fires.

## Alternatives

- **Application checks only.** Simpler, and one bug away from a corrupt ledger.
- **Row-level `BEFORE INSERT` balance check.** Cannot work: when the first line arrives the others are not there yet.

## Consequences

Some logic exists twice, in Java for a good error and in SQL as a backstop. The test suite keeps the two honest.
