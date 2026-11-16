# Runbook: the integrity report is unhealthy

`GET /v1/reports/integrity` returns `"healthy": false`.

## What it means

Each account stores a balance and also has entries. The report recomputes the balance from the entries and lists every account where the two differ, with both numbers. `trialBalanced: false` means stored balances no longer cancel out per currency.

The service writes the entries and the balance in one database transaction, and triggers stop entries from being changed afterwards, so this should never happen through the API. It can happen if someone edited an `account` row by hand, restored a partial backup, or disabled the triggers.

## What to do

1. **Stop writes** to the affected accounts, or the service, while you look. The ledger is append-only, so reads stay safe.
2. **Read the report.** Note each account, its `storedBalance` and its `entriesTotal`. The entries are the record; the stored balance is a cache of them.
3. **Find the cause.** Check who touched the database and when: `pg_stat_activity`, the server log, recent deploys, restores. Look at the statement for the account around the time the numbers diverge:

   ```sql
   select id, transaction_id, side, amount, balance_after
     from ledger_entry where account_code = 'bank' order by id desc limit 20;
   ```

   `balance_after` on each entry is the running balance when it was written. The first entry whose `balance_after` disagrees with the previous one plus its own amount marks where things went wrong.
4. **Repair the cache, not the history.** When the entries are right, set the stored balance back to the entries total:

   ```sql
   update account set balance = (
     select coalesce(sum(case side when 'DEBIT' then amount else -amount end), 0)
       from ledger_entry where account_code = account.code)
    where code = 'bank';
   ```

   Never edit or delete entries or transactions. If an entry itself is wrong, post a reversal and a corrected transaction through the API, so the correction is on the record.
5. **Confirm.** Call the integrity report again and expect `healthy: true`.
6. **Record what happened** where your team keeps incident notes, and find out how the stored balance was able to change.
