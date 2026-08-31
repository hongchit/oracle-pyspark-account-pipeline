# Workflow Demo

This guide presents the legacy Oracle batch and its PySpark replacement one
stage at a time. Each checkpoint lets you inspect the database or generated
files before continuing.

## Preparations

1. Complete the setup in [Getting Started](getting-started.md).
2. Reopen the repository in the Dev Container.
3. Confirm Oracle is healthy and run:

   ```bash
   bash scripts/validate-code.sh
   uv run --frozen pytest
   ```

The demo uses the container's current date by default. The walkthrough below
uses this fixed date so its commands and expected paths remain reproducible:

| Setting | Value |
| --- | --- |
| Processing date | `2026-08-01` |
| Seed | `12` |
| Cleanup | `true` |
| Legacy batch | `LEGACY-20260801-001` |
| PySpark batch | `PYSPARK-20260801-001` |

Cleanup replaces the existing local source and derived Oracle rows. Override
any value with `scripts/run-demo.sh --help`.

## Start the interactive demonstration

```bash
scripts/run-demo.sh
```

At each prompt, press Enter to continue or enter `q` to stop and leave the
current stage available for inspection. The script requires an interactive
terminal by default. Use `--no-pause` only for an unattended run.

## Stage 1: Generate source data

The wrapper builds `legacy-app` and runs its deterministic JPA generator.
With cleanup enabled, it clears prior source and derived rows before inserting
five accounts and eighteen transaction events.

At the first pause, open `sql/demo_inspection.sql` with the Oracle VS Code
extension. Run only its Stage 1 statements to show:

- five accounts;
- normal credit and debit activity;
- two events sharing one transaction ID;
- pending and cancelled events;
- one event referencing an unknown account;
- one account with no activity.

Note: JPA owns source persistence and that no foreign key is placed on
`transaction_event.account_id` because the invalid-account case is deliberate.

## Stage 2: Run the legacy PL/SQL batch

Next, the wrapper installs and calls `BALANCE_BATCH_PKG`. The
package ranks duplicates, rejects the unknown account, aggregates posted
transactions, and left joins all accounts.

At the second pause, run the Stage 2 queries in `sql/demo_inspection.sql`.
Show:

- five rows in `LEGACY_DAILY_BALANCE`;
- one row in `LEGACY_REJECTED_TRANSACTION`;
- zero totals for the no-activity account;
- the legacy batch ID on every reference row.

These tables are the reference result that PySpark must reproduce.

## Stage 3: Run PySpark

The wrapper runs PySpark with reconciliation temporarily skipped. PySpark:

1. reads accounts and the selected date's events through JDBC;
2. selects the newest event by transaction ID;
3. splits valid and rejected winners;
4. calculates exact decimal balances;
5. writes versioned Parquet output.

Oracle is read-only during this stage. The new state exists under:

```text
data/output/processing_date=2026-08-01/
└── batch_id=PYSPARK-20260801-001/
    ├── daily_balances/
    └── rejected_transactions/
```

At the third pause, the Parquet rows from the Dev Container can be inspected:

```bash
uv run --frozen python - <<'PY'
from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("demo-inspection").getOrCreate()
base = (
    "data/output/processing_date=2026-08-01/"
    "batch_id=PYSPARK-20260801-001"
)
spark.read.parquet(f"{base}/daily_balances").orderBy("account_id").show(
    truncate=False
)
spark.read.parquet(f"{base}/rejected_transactions").show(truncate=False)
spark.stop()
PY
```

Compare the displayed shapes with the legacy tables. Reconciliation has not
run yet, so this is a useful point to explain the replacement logic itself.

## Stage 4: Reconcile both paths

Continue to run the standalone reconciliation command. It reads the exact
legacy batch from Oracle and the exact PySpark batch from Parquet, compares all
required fields and rejected IDs, and writes:

```text
reports/reconciliation-summary.json
reports/reconciliation-differences.csv
```

At the final pause, inspect the reports:

```bash
python -m json.tool reports/reconciliation-summary.json
sed -n '1,20p' reports/reconciliation-differences.csv
```

A successful default run shows five rows on each side, one rejection on each
side, zero mismatched accounts, a `0.00` total difference, and a CSV containing
only its header. Exact monetary totals depend on the selected seed.

## Run each stage manually

The same workflow can be presented without the wrapper:

```bash
scripts/generate-data.sh --processing-date 2026-08-01 --seed 12 --cleanup true
scripts/run-legacy.sh --processing-date 2026-08-01 --skip-build
scripts/run-pyspark.sh \
  --processing-date 2026-08-01 \
  --skip-reconciliation
scripts/reconcile.sh --processing-date 2026-08-01
```

Pause between commands and use the same inspection steps above.

## Demonstrate one-cent failure detection

The focused reconciliation test changes one PySpark closing balance by one
cent and confirms the result is `FAIL`:

```bash
uv run --frozen pytest \
  tests/test_reconciliation.py \
  -k one_cent \
  -vv
```

This test writes reports to a temporary test directory, so it does not alter
the live Parquet run or the reports from the main demonstration.

## Recover or repeat

Rerun `scripts/run-demo.sh` to repeat from a clean deterministic dataset. For repeated run after stopping at an intermediate checkpoint, execute another cleanup-enabled run replaces it safely.
