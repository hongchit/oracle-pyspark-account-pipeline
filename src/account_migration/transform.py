"""PySpark transformations for the account balance pipeline."""

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from pyspark.sql import DataFrame, Window
from pyspark.sql import functions as sf
from pyspark.sql.types import DecimalType

_MONEY_TYPE = DecimalType(18, 2)
_COUNT_TYPE = DecimalType(19, 0)


@dataclass(frozen=True)
class TransactionSplit:
    """Valid posted transactions and unknown-account rejections."""

    valid: DataFrame
    rejected: DataFrame


def deduplicate_transactions(transactions: DataFrame) -> DataFrame:
    """Keep the deterministic winning event for each transaction ID."""
    event_order = Window.partitionBy("transaction_id").orderBy(
        sf.col("ingested_at").desc(),
        # Match the PL/SQL tie-breaker when ingestion timestamps are equal.
        sf.col("event_id").desc(),
    )

    return (
        transactions.withColumn(
            "_event_rank", sf.row_number().over(event_order)
        )
        .filter(sf.col("_event_rank") == 1)
        .drop("_event_rank")
    )


def split_valid_and_rejected(
    accounts: DataFrame,
    transactions: DataFrame,
    batch_id: str,
) -> TransactionSplit:
    """Separate valid posted events from unknown-account events."""
    account_ids = accounts.select("account_id")

    # Reject unknown winners before status filtering to match the legacy batch.
    rejected = (
        transactions.join(account_ids, "account_id", "left_anti")
        .withColumn("reason_code", sf.lit("UNKNOWN_ACCOUNT"))
        .withColumn("batch_id", sf.lit(batch_id))
        .select(
            "event_id",
            "transaction_id",
            "account_id",
            "reason_code",
            "batch_id",
        )
    )
    valid = transactions.join(account_ids, "account_id", "inner").filter(
        sf.col("status") == "POSTED"
    )

    return TransactionSplit(valid=valid, rejected=rejected)


def calculate_balances(
    accounts: DataFrame,
    valid_transactions: DataFrame,
    processing_date: date,
    batch_id: str,
) -> DataFrame:
    """Calculate one exact daily balance row for every account."""
    zero_money = sf.lit(Decimal("0.00")).cast(_MONEY_TYPE)
    totals = valid_transactions.groupBy("account_id").agg(
        sf.sum(
            sf.when(
                sf.col("transaction_type") == "CREDIT", sf.col("amount")
            ).otherwise(zero_money)
        )
        .cast(_MONEY_TYPE)
        .alias("total_credit"),
        sf.sum(
            sf.when(
                sf.col("transaction_type") == "DEBIT", sf.col("amount")
            ).otherwise(zero_money)
        )
        .cast(_MONEY_TYPE)
        .alias("total_debit"),
        sf.count(sf.lit(1)).cast(_COUNT_TYPE).alias("transaction_count"),
    )

    balances = accounts.select("account_id", "opening_balance").join(
        totals,
        "account_id",
        "left",
    )
    # Cast after coalescing so empty-account totals keep the target precision.
    balances = (
        balances.withColumn(
            "opening_balance",
            sf.col("opening_balance").cast(_MONEY_TYPE),
        )
        .withColumn(
            "total_credit",
            sf.coalesce(sf.col("total_credit"), zero_money).cast(_MONEY_TYPE),
        )
        .withColumn(
            "total_debit",
            sf.coalesce(sf.col("total_debit"), zero_money).cast(_MONEY_TYPE),
        )
        .withColumn(
            "transaction_count",
            sf.coalesce(
                sf.col("transaction_count"),
                sf.lit(0).cast(_COUNT_TYPE),
            ).cast(_COUNT_TYPE),
        )
        .withColumn(
            "closing_balance",
            (
                sf.col("opening_balance")
                + sf.col("total_credit")
                - sf.col("total_debit")
            ).cast(_MONEY_TYPE),
        )
        .withColumn("balance_date", sf.lit(processing_date).cast("date"))
        .withColumn("batch_id", sf.lit(batch_id))
    )

    return balances.select(
        "account_id",
        "balance_date",
        "opening_balance",
        "total_credit",
        "total_debit",
        "closing_balance",
        "transaction_count",
        "batch_id",
    )
