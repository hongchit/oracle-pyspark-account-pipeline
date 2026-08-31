"""Compare Oracle legacy results with versioned PySpark outputs."""

import csv
import io
import json
import os
import tempfile
from dataclasses import asdict, dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

from pyspark.sql import DataFrame, SparkSession

from account_migration.oracle_reader import OracleJdbcConfig
from account_migration.runner import (
    OutputPaths,
    output_paths_for_run,
    validate_batch_id,
)

_BALANCE_FIELDS = (
    "opening_balance",
    "total_credit",
    "total_debit",
    "closing_balance",
    "transaction_count",
)
_CSV_FIELDS = (
    "record_type",
    "key",
    "difference_type",
    "field",
    "legacy_value",
    "pyspark_value",
)


@dataclass(frozen=True)
class ReconciliationDifference:
    """One balance or rejection difference."""

    record_type: str
    key: str
    difference_type: str
    field: str = ""
    legacy_value: str = ""
    pyspark_value: str = ""


@dataclass(frozen=True)
class ReconciliationResult:
    """Summary and detailed differences for one comparison."""

    processing_date: date
    legacy_batch_id: str
    pyspark_batch_id: str
    status: str
    legacy_rows: int
    pyspark_rows: int
    mismatched_accounts: int
    legacy_rejected: int
    pyspark_rejected: int
    total_balance_difference: Decimal
    differences: tuple[ReconciliationDifference, ...]

    @property
    def passed(self) -> bool:
        """Return whether every required comparison matched."""
        return self.status == "PASS"


@dataclass(frozen=True)
class ReportPaths:
    """Files written for the latest reconciliation run."""

    summary: Path
    differences: Path


def reconcile_results(
    legacy_balances: DataFrame,
    pyspark_balances: DataFrame,
    legacy_rejected: DataFrame,
    pyspark_rejected: DataFrame,
    processing_date: date,
    legacy_batch_id: str,
    pyspark_batch_id: str,
) -> ReconciliationResult:
    """Compare legacy and PySpark balances and rejected event IDs."""
    legacy_rows = legacy_balances.collect()
    pyspark_rows = pyspark_balances.collect()
    legacy_by_key = _balance_rows_by_key(legacy_rows, "legacy")
    pyspark_by_key = _balance_rows_by_key(pyspark_rows, "PySpark")
    differences: list[ReconciliationDifference] = []
    mismatched_keys: set[str] = set()

    for key in sorted(legacy_by_key.keys() | pyspark_by_key.keys()):
        legacy_row = legacy_by_key.get(key)
        pyspark_row = pyspark_by_key.get(key)
        if legacy_row is None:
            mismatched_keys.add(key)
            differences.append(
                ReconciliationDifference(
                    record_type="BALANCE",
                    key=key,
                    difference_type="MISSING_IN_LEGACY",
                )
            )
            continue
        if pyspark_row is None:
            mismatched_keys.add(key)
            differences.append(
                ReconciliationDifference(
                    record_type="BALANCE",
                    key=key,
                    difference_type="MISSING_IN_PYSPARK",
                )
            )
            continue

        for field in _BALANCE_FIELDS:
            legacy_value = _decimal_value(getattr(legacy_row, field))
            pyspark_value = _decimal_value(getattr(pyspark_row, field))
            if legacy_value != pyspark_value:
                mismatched_keys.add(key)
                differences.append(
                    ReconciliationDifference(
                        record_type="BALANCE",
                        key=key,
                        difference_type="VALUE_MISMATCH",
                        field=field,
                        legacy_value=_format_comparison_value(
                            field,
                            legacy_value,
                        ),
                        pyspark_value=_format_comparison_value(
                            field,
                            pyspark_value,
                        ),
                    )
                )

    legacy_rejected_ids = [row.event_id for row in legacy_rejected.collect()]
    pyspark_rejected_ids = [row.event_id for row in pyspark_rejected.collect()]
    legacy_rejected_set = set(legacy_rejected_ids)
    pyspark_rejected_set = set(pyspark_rejected_ids)
    for event_id in sorted(legacy_rejected_set - pyspark_rejected_set):
        differences.append(
            ReconciliationDifference(
                record_type="REJECTION",
                key=event_id,
                difference_type="MISSING_IN_PYSPARK",
            )
        )
    for event_id in sorted(pyspark_rejected_set - legacy_rejected_set):
        differences.append(
            ReconciliationDifference(
                record_type="REJECTION",
                key=event_id,
                difference_type="MISSING_IN_LEGACY",
            )
        )

    legacy_total = sum(
        (_decimal_value(row.closing_balance) for row in legacy_rows),
        Decimal("0.00"),
    )
    pyspark_total = sum(
        (_decimal_value(row.closing_balance) for row in pyspark_rows),
        Decimal("0.00"),
    )
    total_difference = (pyspark_total - legacy_total).quantize(Decimal("0.01"))
    passed = (
        not differences
        and len(legacy_rows) == len(pyspark_rows)
        and len(legacy_rejected_ids) == len(pyspark_rejected_ids)
        and total_difference == Decimal("0.00")
    )

    return ReconciliationResult(
        processing_date=processing_date,
        legacy_batch_id=legacy_batch_id,
        pyspark_batch_id=pyspark_batch_id,
        status="PASS" if passed else "FAIL",
        legacy_rows=len(legacy_rows),
        pyspark_rows=len(pyspark_rows),
        mismatched_accounts=len(mismatched_keys),
        legacy_rejected=len(legacy_rejected_ids),
        pyspark_rejected=len(pyspark_rejected_ids),
        total_balance_difference=total_difference,
        differences=tuple(differences),
    )


def write_reconciliation_reports(
    result: ReconciliationResult,
    report_directory: str | Path = Path("reports"),
) -> ReportPaths:
    """Atomically replace the JSON summary and differences CSV."""
    paths = _report_paths(report_directory)
    summary = {
        "processingDate": result.processing_date.isoformat(),
        "legacyBatchId": result.legacy_batch_id,
        "pysparkBatchId": result.pyspark_batch_id,
        "status": result.status,
        "legacyRows": result.legacy_rows,
        "pysparkRows": result.pyspark_rows,
        "mismatchedAccounts": result.mismatched_accounts,
        "legacyRejected": result.legacy_rejected,
        "pysparkRejected": result.pyspark_rejected,
        "totalBalanceDifference": _format_decimal(
            result.total_balance_difference
        ),
    }
    _atomic_write(paths.summary, json.dumps(summary, indent=2) + "\n")

    csv_output = io.StringIO(newline="")
    writer = csv.DictWriter(
        csv_output,
        fieldnames=_CSV_FIELDS,
        lineterminator="\n",
    )
    writer.writeheader()
    writer.writerows(asdict(difference) for difference in result.differences)
    _atomic_write(paths.differences, csv_output.getvalue())
    return paths


def reconcile_run(
    spark: SparkSession,
    processing_date: date,
    legacy_batch_id: str,
    pyspark_batch_id: str,
    output_root: str | Path = Path("data/output"),
    report_directory: str | Path = Path("reports"),
    config: OracleJdbcConfig | None = None,
) -> tuple[ReconciliationResult, ReportPaths]:
    """Read one legacy/PySpark run, reconcile it, and write reports."""
    validate_batch_id(legacy_batch_id)
    validate_batch_id(pyspark_batch_id)
    jdbc_config = config or OracleJdbcConfig.from_environment()
    legacy_balances, legacy_rejected = _read_legacy_results(
        spark,
        processing_date,
        legacy_batch_id,
        jdbc_config,
    )
    output_paths = output_paths_for_run(
        processing_date,
        pyspark_batch_id,
        output_root,
    )
    pyspark_balances, pyspark_rejected = _read_pyspark_results(
        spark,
        output_paths,
    )
    result = reconcile_results(
        legacy_balances,
        pyspark_balances,
        legacy_rejected,
        pyspark_rejected,
        processing_date,
        legacy_batch_id,
        pyspark_batch_id,
    )
    reports = write_reconciliation_reports(result, report_directory)
    return result, reports


def _read_legacy_results(
    spark: SparkSession,
    processing_date: date,
    batch_id: str,
    config: OracleJdbcConfig,
) -> tuple[DataFrame, DataFrame]:
    next_date = processing_date + timedelta(days=1)
    balances_query = f"""(
        SELECT account_id,
               balance_date,
               opening_balance,
               total_credit,
               total_debit,
               closing_balance,
               transaction_count
          FROM legacy_daily_balance
         WHERE balance_date >= DATE '{processing_date.isoformat()}'
           AND balance_date < DATE '{next_date.isoformat()}'
           AND batch_id = '{batch_id}'
    ) legacy_balance_source"""
    rejected_query = f"""(
        SELECT event_id
          FROM legacy_rejected_transaction
         WHERE batch_id = '{batch_id}'
    ) legacy_rejected_source"""
    return (
        _read_jdbc_query(spark, config, balances_query),
        _read_jdbc_query(spark, config, rejected_query),
    )


def _read_jdbc_query(
    spark: SparkSession,
    config: OracleJdbcConfig,
    query: str,
) -> DataFrame:
    dataframe = spark.read.jdbc(
        url=config.jdbc_url,
        table=query,
        properties={
            "driver": config.driver,
            "user": config.username,
            "password": config.password,
        },
    )
    return dataframe.toDF(*(column.lower() for column in dataframe.columns))


def _read_pyspark_results(
    spark: SparkSession,
    paths: OutputPaths,
) -> tuple[DataFrame, DataFrame]:
    return (
        spark.read.parquet(str(paths.daily_balances)),
        spark.read.parquet(str(paths.rejected_transactions)),
    )


def _balance_rows_by_key(rows: list[Any], source: str) -> dict[str, Any]:
    indexed: dict[str, Any] = {}
    for row in rows:
        key = _balance_key(row)
        if key in indexed:
            raise ValueError(f"Duplicate {source} balance key: {key}")
        indexed[key] = row
    return indexed


def _balance_key(row: Any) -> str:
    balance_date = row.balance_date
    if isinstance(balance_date, datetime):
        balance_date = balance_date.date()
    return f"{_format_value(row.account_id)}|{balance_date.isoformat()}"


def _decimal_value(value: Any) -> Decimal:
    return value if isinstance(value, Decimal) else Decimal(str(value))


def _format_decimal(value: Decimal) -> str:
    return f"{value.quantize(Decimal('0.01')):.2f}"


def _format_comparison_value(field: str, value: Decimal) -> str:
    if field == "transaction_count":
        return format(value.quantize(Decimal("1")), "f")
    return _format_decimal(value)


def _format_value(value: Any) -> str:
    return format(value, "f") if isinstance(value, Decimal) else str(value)


def _report_paths(report_directory: str | Path) -> ReportPaths:
    directory = Path(report_directory)
    return ReportPaths(
        summary=directory / "reconciliation-summary.json",
        differences=directory / "reconciliation-differences.csv",
    )


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as file:
            file.write(content)
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise
