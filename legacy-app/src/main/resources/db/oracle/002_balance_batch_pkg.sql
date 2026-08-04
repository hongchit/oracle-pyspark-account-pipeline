CREATE OR REPLACE PACKAGE balance_batch_pkg AS
    PROCEDURE calculate_daily_balances(
        p_processing_date IN DATE,
        p_batch_id        IN VARCHAR2
    );
END balance_batch_pkg;
/

CREATE OR REPLACE PACKAGE BODY balance_batch_pkg AS
    PROCEDURE calculate_daily_balances(
        p_processing_date IN DATE,
        p_batch_id        IN VARCHAR2
    ) AS
        l_processing_date DATE := TRUNC(p_processing_date);
    BEGIN
        IF p_processing_date IS NULL THEN
            RAISE_APPLICATION_ERROR(-20001, 'Processing date is required');
        END IF;
        IF p_batch_id IS NULL OR TRIM(p_batch_id) IS NULL THEN
            RAISE_APPLICATION_ERROR(-20002, 'Batch ID is required');
        END IF;

        DELETE FROM legacy_daily_balance
         WHERE balance_date = l_processing_date;

        -- Currently supports one daily batch, so rejected output is replaced as a unit.
        DELETE FROM legacy_rejected_transaction;

        INSERT INTO legacy_rejected_transaction (
            event_id,
            transaction_id,
            account_id,
            reason_code,
            batch_id
        )
        WITH ranked_events AS (
            SELECT event_id,
                   transaction_id,
                   account_id,
                   ROW_NUMBER() OVER (
                       PARTITION BY transaction_id
                       -- Event ID makes equal ingestion timestamps deterministic.
                       ORDER BY ingested_at DESC, event_id DESC
                   ) AS event_rank
            FROM transaction_event
            WHERE posting_date >= l_processing_date
              AND posting_date < l_processing_date + 1
        )
        SELECT ranked.event_id,
               ranked.transaction_id,
               ranked.account_id,
               'UNKNOWN_ACCOUNT',
               p_batch_id
        FROM ranked_events ranked
        LEFT JOIN account account_row
            ON account_row.account_id = ranked.account_id
        WHERE ranked.event_rank = 1
          AND account_row.account_id IS NULL;

        INSERT INTO legacy_daily_balance (
            account_id,
            balance_date,
            opening_balance,
            total_credit,
            total_debit,
            closing_balance,
            transaction_count,
            batch_id
        )
        WITH ranked_events AS (
            SELECT transaction_id,
                   account_id,
                   transaction_type,
                   amount,
                   status,
                   ROW_NUMBER() OVER (
                       PARTITION BY transaction_id
                       -- Use the same winner for rejection and balance processing.
                       ORDER BY ingested_at DESC, event_id DESC
                   ) AS event_rank
            FROM transaction_event
            WHERE posting_date >= l_processing_date
              AND posting_date < l_processing_date + 1
        ),
        valid_totals AS (
            SELECT ranked.account_id,
                   SUM(CASE
                       WHEN ranked.transaction_type = 'CREDIT' THEN ranked.amount
                       ELSE 0
                   END) AS total_credit,
                   SUM(CASE
                       WHEN ranked.transaction_type = 'DEBIT' THEN ranked.amount
                       ELSE 0
                   END) AS total_debit,
                   COUNT(*) AS transaction_count
            FROM ranked_events ranked
            JOIN account account_row
                ON account_row.account_id = ranked.account_id
            WHERE ranked.event_rank = 1
              AND ranked.status = 'POSTED'
            GROUP BY ranked.account_id
        )
        SELECT account_row.account_id,
               l_processing_date,
               account_row.opening_balance,
               NVL(totals.total_credit, 0),
               NVL(totals.total_debit, 0),
               account_row.opening_balance
                   + NVL(totals.total_credit, 0)
                   - NVL(totals.total_debit, 0),
               NVL(totals.transaction_count, 0),
               p_batch_id
          FROM account account_row
          LEFT JOIN valid_totals totals
            ON totals.account_id = account_row.account_id;
    END calculate_daily_balances;
END balance_batch_pkg;
/
