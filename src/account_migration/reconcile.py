"""Command-line entry point for standalone result reconciliation."""

import argparse
from collections.abc import Sequence
from datetime import date
from pathlib import Path

from pyspark.sql import SparkSession

from account_migration.reconciliation import (
    ReconciliationResult,
    reconcile_run,
)
from account_migration.runner import validate_batch_id


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
        description="Reconcile Oracle legacy and PySpark balance results.",
    )
    parser.add_argument(
        "--processing-date", required=True, type=_processing_date
    )
    parser.add_argument("--legacy-batch-id", required=True, type=_batch_id)
    parser.add_argument("--pyspark-batch-id", required=True, type=_batch_id)
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


def main(argv: Sequence[str] | None = None) -> int:
    """Run standalone reconciliation and return its process status."""
    arguments = _argument_parser().parse_args(argv)
    spark = SparkSession.builder.appName(
        "oracle-pyspark-reconciliation"
    ).getOrCreate()

    try:
        result, reports = reconcile_run(
            spark,
            arguments.processing_date,
            arguments.legacy_batch_id,
            arguments.pyspark_batch_id,
            arguments.output_dir,
            arguments.report_dir,
        )
    finally:
        spark.stop()

    _print_result(result, reports.summary, reports.differences)
    return 0 if result.passed else 1


def _print_result(
    result: ReconciliationResult,
    summary: Path,
    differences: Path,
) -> None:
    print(f"Reconciliation result: {result.status}")
    print(f"Legacy rows: {result.legacy_rows}")
    print(f"PySpark rows: {result.pyspark_rows}")
    print(f"Mismatched accounts: {result.mismatched_accounts}")
    print(f"Legacy rejected: {result.legacy_rejected}")
    print(f"PySpark rejected: {result.pyspark_rejected}")
    print(f"Total balance difference: {result.total_balance_difference}")
    print(f"Summary report: {summary}")
    print(f"Differences report: {differences}")


if __name__ == "__main__":
    raise SystemExit(main())
