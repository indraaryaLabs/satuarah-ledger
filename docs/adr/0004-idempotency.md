# 0004. Idempotency by key and request fingerprint

**Status:** accepted

## Context

A client that times out does not know whether its payment was applied. Retrying a money-moving call blindly can double-charge; not retrying can lose it.

## Decision

Posting and reversing require an `Idempotency-Key`. The first request with a key is applied and stored with a SHA-256 fingerprint of what it asked for (description, time, postings; the key itself is excluded). A later request with the same key is compared by fingerprint: the same request returns the original transaction (`200`, `Idempotent-Replayed: true`); a different one is `409 IDEMPOTENCY_CONFLICT`. The key has a unique constraint, so two concurrent first requests cannot both win: the loser catches the duplicate and answers from the winner's row.

## Alternatives

- **Key only, ignore the body.** A reused key with a different amount would silently return the old result, hiding a client bug.
- **A separate idempotency table with expiry.** Allows keys to be forgotten, but then a late retry can double-apply. Here the key lives as long as the transaction, forever.

## Consequences

Keys must be unique per business operation (`order-1042-payment`), and a client that wants to repeat the same payment on purpose needs a new key.
