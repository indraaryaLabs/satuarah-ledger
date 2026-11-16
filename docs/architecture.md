# Architecture

## The model

An **account** has a code, a type (`ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`) and one currency. A **transaction** is a set of **postings**, each a debit or a credit of a positive whole amount against one account. A transaction is valid only if its debits equal its credits.

Each account stores one number: debits minus credits, in minor units. A debit-normal account (asset, expense) shows that number as its balance. A credit-normal account (liability, equity, revenue) shows it negated, so every balance reads as a positive number in its natural direction. The API always returns the natural balance.

| Type | Normal side | A debit | A credit |
| --- | --- | --- | --- |
| Asset, expense | debit | increases | decreases |
| Liability, equity, revenue | credit | decreases | increases |

By default an account may not rest on the wrong side of zero: a bank account cannot be negative, and a liability cannot be debited below nothing. `allowNegative` opts an account out, for things like a clearing account.

## Writing a transaction

```
POST /v1/transactions            Idempotency-Key: k
        │
        ▼
   validate shape ── 2 to 100 postings, positive amounts, one currency, balanced
        │
        ▼
   begin transaction
   look up k ── found? ── same fingerprint → return it (replayed)
        │                 different       → IDEMPOTENCY_CONFLICT
        ▼ not found
   lock the accounts, in code order (SELECT … FOR UPDATE)
   check every account exists and shares the currency
   apply the postings in order, remembering each running balance
   check no account ends below its floor
        │
        ▼
   insert transaction + entries, update account balances
   commit ── deferred trigger re-checks that the lines balance
```

Two clients can send the same new key at the same moment. Both find nothing, both try to insert, and the unique constraint on the key lets exactly one win. The loser catches the duplicate, reads the winner's transaction and answers as a replay. This is covered by a concurrency test.

## Why the checks live in two places

The service refuses a bad request with a precise error code before it touches the database. That is the experience a client sees. The database then enforces the same invariants a second time, so that a bug in a later change, a script run by hand or a person with a SQL prompt cannot leave the ledger in a state the service would never have produced. See [ADR 0005](adr/0005-database-enforced-invariants.md).

## Reading

Reads run in a single repeatable-read, read-only transaction, so a statement or a report sees one consistent snapshot and never half of a transaction.

- **Statement.** Entries for an account, ordered by entry id, paged with a cursor (`after`) rather than an offset, so paging stays correct while new entries arrive.
- **Balance as of.** The sum of an account's entries committed at or before the given instant.
- **Trial balance.** Every account's stored balance, totalled per currency. In a sound ledger debits equal credits.
- **Integrity report.** For each account, the stored balance against the sum of its entries. Any difference is listed. See the [runbook](runbook-integrity.md).

## Reversal

Nothing is edited. To undo a transaction, `POST /v1/transactions/{id}/reversal` posts a new transaction with the same lines on the opposite sides and a required reason. A transaction can be reversed once; a reversal cannot itself be reversed (post a new transaction instead). If undoing would push an account below its floor, the reversal is refused, which is correct: the money is no longer there to take back.

## Errors

Every failure is an RFC 9457 `application/problem+json` response with a stable `code`. See [errors.md](errors.md).
