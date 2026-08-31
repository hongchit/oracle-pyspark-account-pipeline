# Business Rules and Data Model

## Daily balance formula

For each account and processing date:

```text
closing balance = opening balance + posted credits - posted debits
```

All monetary values use exact two-decimal precision.

## Transaction rules

1. Only `POSTED` transactions affect balances.
2. `PENDING` and `CANCELLED` winners are excluded.
3. Events sharing a transaction ID are ranked by `ingested_at DESC` and then
   `event_id DESC`; only rank one survives.
4. The event-ID ordering is a deterministic tie-breaker when timestamps match.
5. A winning event for an unknown account is rejected with
   `UNKNOWN_ACCOUNT`, regardless of its status.
6. Every account receives a daily-balance row. An account with no valid
   activity keeps its opening balance.
7. The demo supports one currency and one daily batch.

## Required scenarios

The deterministic generator produces five accounts and eighteen events that
cover normal credits/debits, duplicate transaction events, pending/cancelled
events, one unknown account, and one account with no activity.

## Oracle source tables

### `ACCOUNT`

| Column | Type | Purpose |
| --- | --- | --- |
| `account_id` | `NUMBER(19)` | Primary key |
| `customer_name` | `VARCHAR2(100)` | Customer name |
| `opening_balance` | `NUMBER(18,2)` | Starting balance |
| `account_status` | `VARCHAR2(20)` | Account state |
| `generation_run_id` | `VARCHAR2(64)` | Deterministic dataset identifier |

### `TRANSACTION_EVENT`

| Column | Type | Purpose |
| --- | --- | --- |
| `event_id` | `VARCHAR2(64)` | Unique source event |
| `transaction_id` | `VARCHAR2(64)` | Business transaction identifier |
| `account_id` | `NUMBER(19)` | Scalar account reference |
| `posting_date` | `DATE` | Processing date |
| `transaction_type` | `VARCHAR2(10)` | `CREDIT` or `DEBIT` |
| `amount` | `NUMBER(18,2)` | Exact transaction amount |
| `status` | `VARCHAR2(20)` | `POSTED`, `PENDING`, or `CANCELLED` |
| `ingested_at` | `TIMESTAMP` | Duplicate ordering timestamp |
| `scenario_code` | `VARCHAR2(40)` | Generated scenario label |
| `generation_run_id` | `VARCHAR2(64)` | Deterministic dataset identifier |

`transaction_event.account_id` deliberately has no foreign key because the
unknown-account scenario is part of the business contract.

## Balance and rejection schemas

Both processing paths produce the same logical daily-balance fields:

| Column | Type |
| --- | --- |
| `account_id` | `NUMBER(19)` / Spark decimal |
| `balance_date` | `DATE` |
| `opening_balance` | `NUMBER(18,2)` / Spark `decimal(18,2)` |
| `total_credit` | `NUMBER(18,2)` / Spark `decimal(18,2)` |
| `total_debit` | `NUMBER(18,2)` / Spark `decimal(18,2)` |
| `closing_balance` | `NUMBER(18,2)` / Spark `decimal(18,2)` |
| `transaction_count` | `NUMBER(19)` / Spark `decimal(19,0)` |
| `batch_id` | `VARCHAR2(64)` / string |

Rejected rows contain `event_id`, `transaction_id`, `account_id`,
`reason_code`, and `batch_id`.
