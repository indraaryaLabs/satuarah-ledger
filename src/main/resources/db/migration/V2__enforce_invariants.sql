-- The application checks every rule before it writes. These triggers make the database refuse to
-- hold a ledger that breaks them, even if some future code, or a person with a SQL prompt, tries.

-- 1. History is append-only: no row of a recorded transaction can be changed or removed.
create function ledger_forbid_change() returns trigger
    language plpgsql as
$$
begin
    raise exception 'ledger rows are append-only (% on %)', tg_op, tg_table_name
        using errcode = '55000';
end
$$;

create trigger ledger_transaction_append_only
    before update or delete on ledger_transaction
    for each row execute function ledger_forbid_change();

create trigger ledger_entry_append_only
    before update or delete on ledger_entry
    for each row execute function ledger_forbid_change();

create trigger ledger_transaction_no_truncate
    before truncate on ledger_transaction
    for each statement execute function ledger_forbid_change();

create trigger ledger_entry_no_truncate
    before truncate on ledger_entry
    for each statement execute function ledger_forbid_change();

-- 2. Every transaction balances. Checked when the unit of work commits, after all of its lines
--    are in, so lines can be inserted one at a time.
create function ledger_assert_balanced() returns trigger
    language plpgsql as
$$
declare
    difference bigint;
    line_count integer;
    currencies integer;
begin
    select coalesce(sum(case e.side when 'DEBIT' then e.amount else -e.amount end), 0),
           count(*),
           count(distinct a.currency)
      into difference, line_count, currencies
      from ledger_entry e
      join account a on a.code = e.account_code
     where e.transaction_id = new.transaction_id;

    if line_count < 2 then
        raise exception 'transaction % has fewer than two lines', new.transaction_id
            using errcode = '23514';
    end if;
    if difference <> 0 then
        raise exception 'transaction % is not balanced (debits minus credits = %)', new.transaction_id, difference
            using errcode = '23514';
    end if;
    if currencies <> 1 then
        raise exception 'transaction % mixes currencies', new.transaction_id
            using errcode = '23514';
    end if;
    return null;
end
$$;

create constraint trigger ledger_entry_balanced
    after insert on ledger_entry
    deferrable initially deferred
    for each row execute function ledger_assert_balanced();
