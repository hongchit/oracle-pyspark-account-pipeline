"""Orchestrate and persist the PySpark daily balance pipeline."""

import re
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession

from account_migration.oracle_reader import (
    OracleJdbcConfig,
    read_source_tables,
)
from account_migration.transform import (
    calculate_balances,
    deduplicate_transactions,
    split_valid_and_rejected,
)

_BATCH_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")


@dataclass(frozen=True)
class OutputPaths:
    """Local Parquet locations for one versioned pipeline run."""

    run_directory: Path
    daily_balances: Path
    rejected_transactions: Path


@dataclass(frozen=True)
class PipelineResult:
    """Counts and output paths produced by one pipeline run."""

    outputs: OutputPaths
    balance_row_count: int
    rejected_transaction_count: int


def write_outputs(
    balances: DataFrame,
    rejected_transactions: DataFrame,
    processing_date: date,
    batch_id: str,
    output_root: str | Path = Path("data/output"),
) -> OutputPaths:
    """Write one versioned balance and rejection result as Parquet."""
    paths = output_paths_for_run(processing_date, batch_id, output_root)
    balances.write.mode("overwrite").parquet(str(paths.daily_balances))
    rejected_transactions.write.mode("overwrite").parquet(
        str(paths.rejected_transactions)
    )
    return paths


def output_paths_for_run(
    processing_date: date,
    batch_id: str,
    output_root: str | Path = Path("data/output"),
) -> OutputPaths:
    """Return the versioned output paths for one PySpark run."""
    validate_batch_id(batch_id)
    run_directory = (
        Path(output_root)
        / f"processing_date={processing_date.isoformat()}"
        / f"batch_id={batch_id}"
    )
    paths = OutputPaths(
        run_directory=run_directory,
        daily_balances=run_directory / "daily_balances",
        rejected_transactions=run_directory / "rejected_transactions",
    )
    return paths


def run_pipeline(
    spark: SparkSession,
    processing_date: date,
    batch_id: str,
    output_root: str | Path = Path("data/output"),
    config: OracleJdbcConfig | None = None,
) -> PipelineResult:
    """Read, transform, and write one PySpark daily balance run."""
    validate_batch_id(batch_id)
    sources = read_source_tables(spark, processing_date, config)
    accounts = sources.accounts.cache()
    transactions = sources.transactions.cache()

    try:
        # Materialize JDBC reads once before the two output branches diverge.
        accounts.count()
        transactions.count()

        deduplicated = deduplicate_transactions(transactions)
        split = split_valid_and_rejected(accounts, deduplicated, batch_id)
        balances = calculate_balances(
            accounts,
            split.valid,
            processing_date,
            batch_id,
        )
        balance_row_count = balances.count()
        rejected_transaction_count = split.rejected.count()
        outputs = write_outputs(
            balances,
            split.rejected,
            processing_date,
            batch_id,
            output_root,
        )
        return PipelineResult(
            outputs=outputs,
            balance_row_count=balance_row_count,
            rejected_transaction_count=rejected_transaction_count,
        )
    finally:
        transactions.unpersist()
        accounts.unpersist()


def validate_batch_id(batch_id: str) -> str:
    """Validate and return a filesystem-safe batch ID."""
    if _BATCH_ID_PATTERN.fullmatch(batch_id) is None:
        raise ValueError(
            "Batch ID must be 1-64 characters, start with a letter or digit, "
            "and contain only letters, digits, dots, underscores, or hyphens"
        )
    return batch_id
