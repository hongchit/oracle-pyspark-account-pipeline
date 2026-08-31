-- Read-only queries for the step-by-step workflow demonstration.

-- Stage 1: JPA-generated Oracle source data.
SELECT COUNT(*) AS account_count FROM account;

SELECT account_id,
       customer_name,
       opening_balance,
       account_status,
       generation_run_id
  FROM account
 ORDER BY account_id;

SELECT status,
       scenario_code,
       COUNT(*) AS event_count
  FROM transaction_event
 GROUP BY status, scenario_code
 ORDER BY scenario_code, status;

SELECT event_id,
       transaction_id,
       account_id,
       posting_date,
       transaction_type,
       amount,
       status,
       ingested_at,
       scenario_code
  FROM transaction_event
 ORDER BY transaction_id, ingested_at, event_id;

-- Stage 2: PL/SQL reference outputs.
SELECT account_id,
       balance_date,
       opening_balance,
       total_credit,
       total_debit,
       closing_balance,
       transaction_count,
       batch_id
  FROM legacy_daily_balance
 ORDER BY balance_date, account_id;

SELECT event_id,
       transaction_id,
       account_id,
       reason_code,
       batch_id
  FROM legacy_rejected_transaction
 ORDER BY event_id;
