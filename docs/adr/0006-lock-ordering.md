# 0006. Pessimistic row locks, taken in a fixed order

**Status:** accepted

## Context

Two transactions that both spend from the same account must not both see the old balance. Two transactions that touch the same accounts in opposite order can deadlock.

## Decision

A write locks every account it touches with `SELECT … FOR UPDATE`, sorted by account code, before it reads a balance. Concurrent writers to the same account queue; writers to disjoint accounts do not wait on each other. Because everyone locks in the same order, no cycle can form. Reads take no locks and use a repeatable-read snapshot.

## Alternatives

- **Optimistic versioning with retries.** Fine at low contention, but a hot account (a bank account, a revenue account) turns into retry storms.
- **`SERIALIZABLE` isolation.** Correct, but pushes serialisation failures onto every writer and still needs retries.
- **A single writer thread.** Simple and correct, but limits throughput to one core and complicates failure handling.

## Consequences

Throughput on a single hot account is bounded by how fast its lock turns over. For the workloads this is meant for, that is the right trade: exact balances over raw speed. If one account ever becomes the bottleneck, split it into sub-accounts and sum them on read.
