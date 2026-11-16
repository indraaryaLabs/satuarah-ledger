# 0002. History is append-only; corrections are reversals

**Status:** accepted

## Context

A ledger is evidence. If a row can be edited, then what it said yesterday is no longer knowable, and an auditor has to trust that nobody did.

## Decision

Transactions and entries are never updated or deleted. PostgreSQL triggers raise an error on any `UPDATE`, `DELETE` or `TRUNCATE` of those tables. To undo a transaction, post a new one with the same lines on opposite sides, linked by `reversal_of` and carrying a reason. A unique constraint lets a transaction be reversed once, and a reversal cannot itself be reversed; a mistaken reversal is corrected by posting the original again as a new transaction.

## Alternatives

- **Soft delete with a `voided` flag.** Cheaper to read, but it mutates history and every balance query has to remember to filter.
- **Allow edits with an audit table.** Moves the trust problem into a second table that can also be edited.

## Consequences

Reads never need to ask "was this voided". The cost is that a reversal that would overdraw an account is refused, which is the correct answer: the money is no longer there to take back, and the operator must unwind later activity first.
