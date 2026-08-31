from datetime import datetime
from decimal import Decimal

import pytest
from pyspark.sql import SparkSession

from account_migration import (
    calculate_balances,
    deduplicate_transactions,
    split_valid_and_rejected,
)


@pytest.fixture(scope="module")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[2]")
        .appName("account-migration-transform-tests")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


# Test case for deduplicate_transactions to ensure that
# it selects deterministic winners based on
# the latest ingested_at timestamp and status.
def test_deduplicate_transactions_selects_deterministic_winners(
    spark: SparkSession,
) -> None:
    timestamp = datetime(2026, 8, 1, 9, 0)
    transactions = spark.createDataFrame(
        [
            ("EVT-001", "TXN-1", timestamp, "POSTED"),
            ("EVT-002", "TXN-1", datetime(2026, 8, 1, 10, 0), "POSTED"),
            ("EVT-010", "TXN-2", timestamp, "POSTED"),
            ("EVT-011", "TXN-2", timestamp, "POSTED"),
            ("EVT-020", "TXN-3", timestamp, "POSTED"),
            ("EVT-021", "TXN-3", datetime(2026, 8, 1, 11, 0), "PENDING"),
        ],
        ["event_id", "transaction_id", "ingested_at", "status"],
    )

    winners = {
        row.transaction_id: (row.event_id, row.status)
        for row in deduplicate_transactions(transactions).collect()
    }

    assert winners == {
        "TXN-1": ("EVT-002", "POSTED"),
        "TXN-2": ("EVT-011", "POSTED"),
        "TXN-3": ("EVT-021", "PENDING"),
    }


# Test case for split_valid_and_rejected to ensure that
# it correctly applies account and status rules,
# separating valid transactions from rejected ones
# based on account existence and transaction status.
def test_split_valid_and_rejected_applies_account_and_status_rules(
    spark: SparkSession,
) -> None:
    accounts = spark.createDataFrame([(101,), (102,)], ["account_id"])
    transactions = spark.createDataFrame(
        [
            ("EVT-001", "TXN-1", 101, "POSTED"),
            ("EVT-002", "TXN-2", 101, "PENDING"),
            ("EVT-003", "TXN-3", 102, "CANCELLED"),
            ("EVT-004", "TXN-4", 999, "PENDING"),
        ],
        ["event_id", "transaction_id", "account_id", "status"],
    )

    split = split_valid_and_rejected(
        accounts,
        transactions,
        "PYSPARK-20260801-TEST",
    )

    assert [row.event_id for row in split.valid.collect()] == ["EVT-001"]
    assert split.rejected.columns == [
        "event_id",
        "transaction_id",
        "account_id",
        "reason_code",
        "batch_id",
    ]
    assert split.rejected.collect()[0].asDict() == {
        "event_id": "EVT-004",
        "transaction_id": "TXN-4",
        "account_id": 999,
        "reason_code": "UNKNOWN_ACCOUNT",
        "batch_id": "PYSPARK-20260801-TEST",
    }


# Test case for calculate_balances to ensure that
# it correctly computes balances for accounts,
# including those with no activity, and that the
# resulting DataFrame has the expected schema and values.
def test_calculate_balances_includes_active_and_no_activity_accounts(
    spark: SparkSession,
) -> None:
    accounts = spark.createDataFrame(
        [
            (101, Decimal("100.00")),
            (102, Decimal("250.25")),
        ],
        ["account_id", "opening_balance"],
    )
    valid_transactions = spark.createDataFrame(
        [
            (101, "CREDIT", Decimal("25.50")),
            (101, "DEBIT", Decimal("10.25")),
        ],
        ["account_id", "transaction_type", "amount"],
    )

    balances = calculate_balances(
        accounts,
        valid_transactions,
        datetime(2026, 8, 1).date(),
        "PYSPARK-20260801-TEST",
    )
    rows = {row.account_id: row for row in balances.collect()}

    assert balances.columns == [
        "account_id",
        "balance_date",
        "opening_balance",
        "total_credit",
        "total_debit",
        "closing_balance",
        "transaction_count",
        "batch_id",
    ]
    assert balances.schema["opening_balance"].dataType.simpleString() == (
        "decimal(18,2)"
    )
    assert balances.schema["total_credit"].dataType.simpleString() == (
        "decimal(18,2)"
    )
    assert balances.schema["total_debit"].dataType.simpleString() == (
        "decimal(18,2)"
    )
    assert balances.schema["closing_balance"].dataType.simpleString() == (
        "decimal(18,2)"
    )
    assert balances.schema["transaction_count"].dataType.simpleString() == (
        "decimal(19,0)"
    )
    assert rows[101].opening_balance == Decimal("100.00")
    assert rows[101].total_credit == Decimal("25.50")
    assert rows[101].total_debit == Decimal("10.25")
    assert rows[101].closing_balance == Decimal("115.25")
    assert rows[101].transaction_count == Decimal("2")
    assert rows[102].opening_balance == Decimal("250.25")
    assert rows[102].total_credit == Decimal("0.00")
    assert rows[102].total_debit == Decimal("0.00")
    assert rows[102].closing_balance == Decimal("250.25")
    assert rows[102].transaction_count == Decimal("0")
    assert rows[102].batch_id == "PYSPARK-20260801-TEST"
