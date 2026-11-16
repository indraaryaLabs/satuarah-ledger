# Architecture decision records

Decisions that were not obvious, with the alternatives that were weighed.

| # | Decision |
| --- | --- |
| [0001](0001-integer-minor-units.md) | Amounts are whole numbers of minor units |
| [0002](0002-append-only-and-reversal.md) | History is append-only; corrections are reversals |
| [0003](0003-denormalised-balance.md) | Store a balance on each account, and prove it |
| [0004](0004-idempotency.md) | Idempotency by key and request fingerprint |
| [0005](0005-database-enforced-invariants.md) | The database enforces the invariants too |
| [0006](0006-lock-ordering.md) | Pessimistic row locks, taken in a fixed order |
