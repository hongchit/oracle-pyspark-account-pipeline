"""Command-line entry point for the PySpark daily balance pipeline."""

import argparse
from collections.abc import Sequence
from datetime import date
from pathlib import Path

from pyspark.sql import SparkSession

from account_migration.reconcile import _print_result
from account_migration.reconciliation import reconcile_run
from account_migration.runner import run_pipeline, validate_batch_id


def _processing_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            "Processing date must use YYYY-MM-DD"
        ) from error


def _batch_id(value: str) -> str:
    try:
        return validate_batch_id(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from error


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Calculate daily account balances with PySpark.",
    )
    parser.add_argument(
        "--processing-date",
        required=True,
        type=_processing_date,
    )
    parser.add_argument("--batch-id", required=True, type=_batch_id)
    parser.add_argument("--legacy-batch-id", type=_batch_id)
    parser.add_argument(
        "--skip-reconciliation",
        action="store_true",
        help="Write PySpark outputs without comparing the legacy batch.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("data/output"),
    )
    parser.add_argument(
        "--report-dir",
        type=Path,
        default=Path("reports"),
    )
    return parser


def _parse_arguments(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = _argument_parser()
    arguments = parser.parse_args(argv)
    if not arguments.skip_reconciliation and arguments.legacy_batch_id is None:
        message = "--legacy-batch-id is required without --skip-reconciliation"
        parser.error(message)
    return arguments


def main(argv: Sequence[str] | None = None) -> int:
    """Run the PySpark pipeline command."""
    arguments = _parse_arguments(argv)
    spark = SparkSession.builder.appName(
        "oracle-pyspark-account-pipeline"
    ).getOrCreate()
    reconciliation = None
    reports = None

    try:
        result = run_pipeline(
            spark,
            arguments.processing_date,
            arguments.batch_id,
            arguments.output_dir,
        )
        if not arguments.skip_reconciliation:
            reconciliation, reports = reconcile_run(
                spark,
                arguments.processing_date,
                arguments.legacy_batch_id,
                arguments.batch_id,
                arguments.output_dir,
                arguments.report_dir,
            )
    finally:
        spark.stop()

    print("PySpark batch completed.")
    print(f"Balance rows: {result.balance_row_count}")
    print(f"Rejected transactions: {result.rejected_transaction_count}")
    print(f"Daily balances: {result.outputs.daily_balances}")
    print(f"Rejected output: {result.outputs.rejected_transactions}")
    if reconciliation is None or reports is None:
        print("Reconciliation skipped.")
        return 0

    _print_result(reconciliation, reports.summary, reports.differences)
    return 0 if reconciliation.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
