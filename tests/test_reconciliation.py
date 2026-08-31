import csv
import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest
from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as sf

from account_migration import (
    reconcile_results,
    write_reconciliation_reports,
)


@pytest.fixture(scope="module")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[2]")
        .appName("account-migration-reconciliation-tests")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


@pytest.fixture
def matching_results(
    spark: SparkSession,
) -> tuple[DataFrame, DataFrame, DataFrame, DataFrame]:
    balances = spark.createDataFrame(
        [
            (
                101,
                date(2026, 8, 1),
                Decimal("100.00"),
                Decimal("25.50"),
                Decimal("10.25"),
                Decimal("115.25"),
                Decimal("2"),
            ),
            (
                102,
                date(2026, 8, 1),
                Decimal("250.25"),
                Decimal("0.00"),
                Decimal("0.00"),
                Decimal("250.25"),
                Decimal("0"),
            ),
        ],
        [
            "account_id",
            "balance_date",
            "opening_balance",
            "total_credit",
            "total_debit",
            "closing_balance",
            "transaction_count",
        ],
    )
    rejected = spark.createDataFrame([("EVT-999",)], ["event_id"])
    return balances, balances, rejected, rejected


def test_reconciliation_pass_writes_empty_differences_report(
    matching_results: tuple[DataFrame, DataFrame, DataFrame, DataFrame],
    tmp_path: Path,
) -> None:
    result = reconcile_results(
        *matching_results,
        date(2026, 8, 1),
        "LEGACY-20260801-TEST",
        "PYSPARK-20260801-TEST",
    )
    reports = write_reconciliation_reports(result, tmp_path)

    summary = json.loads(reports.summary.read_text(encoding="utf-8"))
    with reports.differences.open(encoding="utf-8", newline="") as file:
        differences = list(csv.DictReader(file))

    assert result.passed
    assert summary["status"] == "PASS"
    assert summary["legacyRows"] == 2
    assert summary["pysparkRows"] == 2
    assert summary["mismatchedAccounts"] == 0
    assert summary["legacyRejected"] == 1
    assert summary["pysparkRejected"] == 1
    assert summary["totalBalanceDifference"] == "0.00"
    assert differences == []


def test_reconciliation_fails_for_one_cent_closing_balance_difference(
    matching_results: tuple[DataFrame, DataFrame, DataFrame, DataFrame],
    tmp_path: Path,
) -> None:
    legacy_balances, pyspark_balances, legacy_rejected, pyspark_rejected = (
        matching_results
    )
    changed_balances = pyspark_balances.withColumn(
        "closing_balance",
        sf.when(
            sf.col("account_id") == 101,
            sf.col("closing_balance") + sf.lit(Decimal("0.01")),
        ).otherwise(sf.col("closing_balance")),
    )
    result = reconcile_results(
        legacy_balances,
        changed_balances,
        legacy_rejected,
        pyspark_rejected,
        date(2026, 8, 1),
        "LEGACY-20260801-TEST",
        "PYSPARK-20260801-TEST",
    )
    reports = write_reconciliation_reports(result, tmp_path)

    summary = json.loads(reports.summary.read_text(encoding="utf-8"))
    with reports.differences.open(encoding="utf-8", newline="") as file:
        differences = list(csv.DictReader(file))

    assert not result.passed
    assert summary["status"] == "FAIL"
    assert summary["mismatchedAccounts"] == 1
    assert summary["totalBalanceDifference"] == "0.01"
    assert differences == [
        {
            "record_type": "BALANCE",
            "key": "101|2026-08-01",
            "difference_type": "VALUE_MISMATCH",
            "field": "closing_balance",
            "legacy_value": "115.25",
            "pyspark_value": "115.26",
        }
    ]
