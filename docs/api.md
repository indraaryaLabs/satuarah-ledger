# API

Base path `/v1`. JSON in and out. Amounts are whole numbers of minor units. Times are ISO-8601 instants in UTC. Errors are described in [errors.md](errors.md).

## Accounts

`POST /v1/accounts` opens an account. `201` with a `Location` header.

```json
{ "code": "bank", "name": "Bank", "type": "ASSET", "currency": "IDR", "allowNegative": false }
```

`code` is 1 to 32 characters of letters, digits, `.`, `_` or `-`. `type` is one of `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`. `allowNegative` defaults to `false`.

`GET /v1/accounts` lists them. `GET /v1/accounts/{code}` returns one:

```json
{ "code": "bank", "name": "Bank", "type": "ASSET", "normalSide": "DEBIT",
  "currency": "IDR", "allowNegative": false, "balance": 189000,
  "createdAt": "2026-11-01T02:00:00Z" }
```

`balance` is in the account's natural direction, so a revenue account that has earned 63.000 shows `63000`.

## Transactions

`POST /v1/transactions` requires an `Idempotency-Key` header: 1 to 255 printable ASCII characters, no spaces. Use something unique per business operation, such as `order-1042-payment`.

```json
{
  "description": "Pro plan, 3 months, paid up front",
  "occurredAt": "2026-11-01T02:00:00Z",
  "postings": [
    { "account": "bank", "side": "DEBIT", "amount": 189000 },
    { "account": "deferred-revenue", "side": "CREDIT", "amount": 189000 }
  ]
}
```

`occurredAt` is optional and defaults to the commit time. `description` is 1 to 500 characters. There must be 2 to 100 postings, in one currency, and debits must equal credits. An amount is 1 to 10^15.

| Outcome | Status | Headers |
| --- | --- | --- |
| Applied | `201` | `Location: /v1/transactions/{id}` |
| Same key, same request (a retry) | `200` | `Idempotent-Replayed: true` |
| Same key, different request | `409` `IDEMPOTENCY_CONFLICT` | |

The response lists the lines as stored. `sequence` is a strictly increasing number that orders transactions by when they were recorded. It can skip values, because a rolled-back write still consumes one.

`GET /v1/transactions/{id}` reads one back.

### Reversal

```bash
curl -s localhost:8080/v1/transactions/$ID/reversal \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: refund-1042' \
  -d '{"reason":"Customer refund"}'
```

Posts the opposite lines as a new transaction whose `reversalOf` is the original. `201` the first time, `200` on a retry with the same key. A second reversal of the same transaction is `409 ALREADY_REVERSED`; reversing a reversal is `422 NOT_REVERSIBLE`.

## Reading balances

`GET /v1/accounts/{code}/statement?limit=50&after=0` returns entries in order with the account's running balance after each:

```json
{ "account": "bank", "currency": "IDR",
  "lines": [ { "entryId": 1, "transactionId": "…", "committedAt": "…",
               "description": "…", "side": "DEBIT", "amount": 189000, "balanceAfter": 189000 } ],
  "nextAfter": null }
```

`limit` is 1 to 200. When a page is full, `nextAfter` is the cursor for the next one; pass it as `after`. The last page can be exactly full, in which case the next request returns an empty page and `nextAfter: null`.

`GET /v1/accounts/{code}/balance?asOf=2026-11-30T23:59:59Z` returns the balance as it stood at that instant. Without `asOf` it uses the current time.

## Reports

`GET /v1/reports/trial-balance` totals every account per currency and says whether debits equal credits.

`GET /v1/reports/integrity` compares each account's stored balance with the sum of its entries. `healthy` is `true` when nothing differs. If it is ever `false`, follow the [runbook](runbook-integrity.md).

## Health

`GET /actuator/health` for liveness and readiness.

## A worked example

[`SubscriptionLifecycleTest`](../src/test/java/click/satuarah/ledger/scenario/SubscriptionLifecycleTest.java) follows one quarterly plan from payment to refund:

1. **Payment.** Bank debited and deferred revenue credited, 189.000. The money is held, not yet earned.
2. **Each month served.** 63.000 moves from deferred revenue to subscription revenue.
3. **Refund.** Reversing the payment is refused while two months are recognised, because deferred revenue holds only 63.000. The two recognitions are reversed first, then the payment, and every account returns to zero. All of it stays on the statement.
