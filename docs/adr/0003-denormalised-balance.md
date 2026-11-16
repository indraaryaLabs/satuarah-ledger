# 0003. Store a balance on each account, and prove it

**Status:** accepted

## Context

An account's balance is the sum of its entries. Recomputing it for every request makes writes slow as history grows, and the overdraft check needs the current balance under a lock.

## Decision

Each account row stores `balance` (debits minus credits) and each entry stores `balance_after`. Both are written in the same database transaction as the entry. A `CHECK` constraint keeps a non-overdraft account on its natural side of zero. Because a cached number can drift from the facts it summarises, `GET /v1/reports/integrity` recomputes every balance from the entries and lists any difference, and the [runbook](../runbook-integrity.md) says what to do.

## Alternatives

- **Compute balances on demand.** Always right, but unbounded work per write and per read.
- **Materialised view.** Stale between refreshes, which is the wrong trade-off for an overdraft check.

## Consequences

The balance is a cache of the entries, and the entries win. Drift is detectable rather than silent, and the repair is one statement that never touches history.
