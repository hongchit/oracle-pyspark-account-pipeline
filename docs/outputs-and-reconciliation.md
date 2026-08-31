# Outputs and Reconciliation

## Versioned PySpark output

Each PySpark run writes two Parquet datasets:

```text
data/output/
└── processing_date=2026-08-01/
    └── batch_id=PYSPARK-20260801-001/
        ├── daily_balances/
        └── rejected_transactions/
```

Writing uses Spark overwrite mode in two children directories. Rerunning the
same date and batch replaces that run without removing other versions. If the
second write fails, rerun the same command to repair the partial run.

## Reconciliation inputs

Reconciliation requires:

- a processing date;
- the exact Oracle legacy batch ID;
- the exact PySpark output batch ID;
- optional output and report roots.

The legacy balance and rejection tables are filtered by the supplied legacy
batch ID. The PySpark datasets are read from the versioned path.

## Exact comparisons

Balances are keyed by account ID and balance date. Reconciliation compares:

- opening balance;
- total credit;
- total debit;
- closing balance;
- transaction count;
- balance row count;
- rejected event-ID membership and count;
- total closing balance.

There is no tolerance. A one-cent difference fails. Total balance difference
is calculated as PySpark total minus legacy total.

## Reports

The latest comparison atomically replaces:

```text
reports/reconciliation-summary.json
reports/reconciliation-differences.csv
```

Example summary:

```json
{
  "processingDate": "2026-08-01",
  "legacyBatchId": "LEGACY-20260801-001",
  "pysparkBatchId": "PYSPARK-20260801-001",
  "status": "PASS",
  "legacyRows": 5,
  "pysparkRows": 5,
  "mismatchedAccounts": 0,
  "legacyRejected": 1,
  "pysparkRejected": 1,
  "totalBalanceDifference": "0.00"
}
```

The CSV always contains this header:

```text
record_type,key,difference_type,field,legacy_value,pyspark_value
```

Balance value mismatches use one row per field. Missing balance rows and
rejected event IDs use `MISSING_IN_LEGACY` or `MISSING_IN_PYSPARK`.

## Commands and exit status

PySpark reconciles automatically when a legacy batch ID is supplied:

```bash
scripts/run-pyspark.sh \
  --processing-date 2026-08-01 \
  --legacy-batch-id LEGACY-20260801-001
```

Reconcile existing outputs without rerunning PySpark:

```bash
scripts/reconcile.sh \
  --processing-date 2026-08-01 \
  --legacy-batch-id LEGACY-20260801-001 \
  --pyspark-batch-id PYSPARK-20260801-001
```

Both commands return status `0` for `PASS` and `1` for `FAIL`. Missing Parquet,
JDBC errors, and invalid arguments are operational failures and propagate
without being converted into comparison differences.
