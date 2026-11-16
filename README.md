# satuarah-ledger

A double-entry ledger service. It records money moving between accounts and refuses to record anything that does not add up.

[![ci](https://github.com/indraaryaLabs/satuarah-ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/indraaryaLabs/satuarah-ledger/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-informational)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.1-informational)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-informational)

I built it to practise the part of backend work where being almost right is wrong: money. The interesting rules are in the domain and in the database, and they are tested against a real PostgreSQL rather than a mock.

## What it guarantees

| Guarantee | Enforced by |
| --- | --- |
| Every transaction balances: total debits equal total credits, in one currency | the service, and a deferred PostgreSQL constraint trigger |
| History is append-only: nothing recorded is updated, deleted or truncated | PostgreSQL triggers; a correction is a new, opposite transaction |
| A request retried after a timeout is applied once | a required `Idempotency-Key` plus a fingerprint of the request |
| A key reused for a different request is rejected, not silently ignored | the fingerprint comparison |
| An account that must not go negative never does, even under concurrent writers | row locks taken in a fixed order, plus a `CHECK` constraint |
| A transaction is reversed at most once, and a reversal cannot be reversed | a unique constraint on `reversal_of` |
| Stored balances can be proven against the entries they came from | `GET /v1/reports/integrity` |

Amounts are whole numbers of minor units (`189000` is Rp 189.000, `1999` is USD 19.99). There are no floats anywhere, and the API refuses a fractional amount instead of rounding it.

## Run it

You need Docker.

```bash
docker compose up --build
```

The API listens on `http://localhost:8080`. Open two accounts and move some money:

```bash
curl -s localhost:8080/v1/accounts -H 'Content-Type: application/json' \
  -d '{"code":"bank","name":"Bank","type":"ASSET","currency":"IDR"}'
curl -s localhost:8080/v1/accounts -H 'Content-Type: application/json' \
  -d '{"code":"deferred-revenue","name":"Deferred revenue","type":"LIABILITY","currency":"IDR"}'

curl -s localhost:8080/v1/transactions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1042-payment' \
  -d '{"description":"Pro plan, 3 months, paid up front","postings":[
        {"account":"bank","side":"DEBIT","amount":189000},
        {"account":"deferred-revenue","side":"CREDIT","amount":189000}]}'
```

Send the same request again and you get the same transaction back with `200` and an `Idempotent-Replayed: true` header, and nothing is posted twice. The full walkthrough, including a refund, is in [docs/api.md](docs/api.md).

## Develop

```bash
mvn verify          # needs Docker: the tests start PostgreSQL with Testcontainers
```

Java 21 and Maven 3.9 are all you need. `verify` runs every test and fails the build if line coverage falls below 90%.

## How it is built

The core has no framework in it. The Spring and JDBC parts are a thin layer on top.

```
domain        records and rules: Account, NewTransaction, Posting, errors. No I/O.
application   LedgerService: the use cases. Depends on a LedgerStore interface only.
store         PostgresLedgerStore: JDBC, row locks, snapshot reads.
web           LedgerController: translates HTTP to the service and back.
```

The rules are written once as an abstract contract test, [`LedgerServiceContract`](src/test/java/click/satuarah/ledger/application/LedgerServiceContract.java), and run against two stores: a small in-memory one and PostgreSQL. A store that passes the contract is a correct place to keep a ledger. Three of its tests hammer the service from many threads and check that money is neither lost nor created.

Beyond the contract there are tests that go around the application and talk to the database directly, to prove PostgreSQL refuses an unbalanced or edited ledger on its own. The API is tested over real HTTP against a running server. [docs/architecture.md](docs/architecture.md) explains the design; the decisions that were not obvious are written up as [ADRs](docs/adr/README.md).

## Limits, stated plainly

- One ledger, one database. There is no multi-tenancy and no authentication; put it behind a gateway that does that.
- A transaction holds row locks on every account it touches. Two payments into the same busy account queue behind each other. That is the price of exact, race-free balances, and [ADR 0006](docs/adr/0006-lock-ordering.md) explains why it is acceptable here.
- A refund reverses a whole transaction. Partial refunds are a new transaction that you write.
- There is no currency conversion. A transaction is in one currency; moving value between currencies is two transactions through an account you define.

## Licence

MIT. See [LICENSE](LICENSE).
