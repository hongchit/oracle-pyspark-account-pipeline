select * from user_tables order by table_name;

desc account;
select * from account order by account_id;

desc transaction_event;
select * from transaction_event order by event_id;

desc legacy_daily_balance;
select * from legacy_daily_balance order by account_id, balance_date;

desc legacy_rejected_transaction;
select * from legacy_rejected_transaction order by account_id, event_id;

delete from account;
delete from transaction_event;
delete from legacy_daily_balance;
delete from legacy_rejected_transaction;
commit;