-- The ledger schema. Amounts are whole numbers of minor units (rupiah for IDR, cents for USD).
-- A balance is debits minus credits, so it is positive for a net debit and negative for a net credit.

create table account (
    code           text        primary key
                   check (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$'),
    name           text        not null
                   check (char_length(name) between 1 and 120),
    type           text        not null
                   check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency       text        not null
                   check (currency ~ '^[A-Z]{3}$'),
    allow_negative boolean     not null default false,
    balance        bigint      not null default 0,
    created_at     timestamptz not null,
    -- An account that does not allow it can never rest on the wrong side of zero.
    constraint account_not_overdrawn check (
        allow_negative
        or (type in ('ASSET', 'EXPENSE') and balance >= 0)
        or (type in ('LIABILITY', 'EQUITY', 'REVENUE') and balance <= 0)
    )
);

create table ledger_transaction (
    id              uuid        primary key,
    seq             bigint      generated always as identity,
    idempotency_key text        not null,
    fingerprint     text        not null,
    description     text        not null
                    check (char_length(description) between 1 and 500),
    currency        text        not null
                    check (currency ~ '^[A-Z]{3}$'),
    occurred_at     timestamptz not null,
    committed_at    timestamptz not null,
    reversal_of     uuid        references ledger_transaction (id),
    reason          text,
    constraint uq_transaction_seq unique (seq),
    constraint uq_transaction_idempotency_key unique (idempotency_key),
    -- A transaction can be reversed once.
    constraint uq_transaction_reversal_of unique (reversal_of),
    constraint reversal_has_reason check (reversal_of is null or reason is not null),
    constraint reversal_is_not_self check (reversal_of is null or reversal_of <> id)
);

create table ledger_entry (
    id             bigint generated always as identity primary key,
    transaction_id uuid    not null references ledger_transaction (id),
    line_no        integer not null check (line_no >= 1),
    account_code   text    not null references account (code),
    side           text    not null check (side in ('DEBIT', 'CREDIT')),
    amount         bigint  not null check (amount > 0),
    balance_after  bigint  not null,
    constraint uq_entry_line unique (transaction_id, line_no)
);

create index ix_entry_account on ledger_entry (account_code, id);
create index ix_transaction_committed on ledger_transaction (committed_at);
