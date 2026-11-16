# Errors

Failures use `application/problem+json`. Branch on `code`, never on the message.

```json
{
  "type": "about:blank",
  "title": "INSUFFICIENT_FUNDS",
  "status": 422,
  "detail": "account bank would go below zero",
  "code": "INSUFFICIENT_FUNDS"
}
```

| `code` | HTTP | Meaning |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | Malformed JSON, a missing header or field, a fractional or non-numeric amount, a bad id or timestamp, a limit out of range. |
| `UNBALANCED` | 422 | Debits and credits are not equal. |
| `CURRENCY_MISMATCH` | 422 | The postings span more than one currency, or an account's currency differs from the transaction's. |
| `INSUFFICIENT_FUNDS` | 422 | An account would end on the wrong side of zero and does not allow it. |
| `NOT_REVERSIBLE` | 422 | The transaction is itself a reversal. |
| `ACCOUNT_NOT_FOUND` | 404 | No account with that code. |
| `TRANSACTION_NOT_FOUND` | 404 | No transaction with that id. |
| `NOT_FOUND` | 404 | No such route. |
| `ACCOUNT_EXISTS` | 409 | An account with that code is already open. |
| `IDEMPOTENCY_CONFLICT` | 409 | The `Idempotency-Key` was already used for a different request. |
| `ALREADY_REVERSED` | 409 | The transaction has already been reversed. |
| `INTERNAL_ERROR` | 500 | Unexpected. Nothing was applied. The cause is in the server log. |

A `4xx` means the request was understood and refused; fixing the request is the only remedy. Retrying a `5xx` with the same `Idempotency-Key` is always safe.
