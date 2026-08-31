from datetime import date
from pathlib import Path

import pytest
from pyspark.sql import SparkSession

from account_migration import write_outputs
from account_migration.pipeline import _parse_arguments


@pytest.fixture(scope="module")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[2]")
        .appName("account-migration-pipeline-tests")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


# Test case for write_outputs to ensure that
# it correctly writes outputs for different batch IDs,
# overwriting only the matching batch and
# preserving others.
def test_write_outputs_versions_runs_and_overwrites_only_matching_batch(
    spark: SparkSession,
    tmp_path: Path,
) -> None:
    rejected = spark.createDataFrame(
        [("EVT-001", "UNKNOWN_ACCOUNT")],
        ["event_id", "reason_code"],
    )
    first_paths = write_outputs(
        spark.createDataFrame([(101,)], ["account_id"]),
        rejected,
        date(2026, 8, 1),
        "PYSPARK-20260801-A",
        tmp_path,
    )
    second_paths = write_outputs(
        spark.createDataFrame([(202,)], ["account_id"]),
        rejected,
        date(2026, 8, 1),
        "PYSPARK-20260801-B",
        tmp_path,
    )
    write_outputs(
        spark.createDataFrame([(303,)], ["account_id"]),
        rejected,
        date(2026, 8, 1),
        "PYSPARK-20260801-A",
        tmp_path,
    )

    first_accounts = spark.read.parquet(
        str(first_paths.daily_balances)
    ).collect()
    second_accounts = spark.read.parquet(
        str(second_paths.daily_balances)
    ).collect()
    rejected_rows = spark.read.parquet(
        str(first_paths.rejected_transactions)
    ).collect()

    assert first_paths.run_directory == (
        tmp_path / "processing_date=2026-08-01" / "batch_id=PYSPARK-20260801-A"
    )
    assert [row.account_id for row in first_accounts] == [303]
    assert [row.account_id for row in second_accounts] == [202]
    assert rejected_rows[0].reason_code == "UNKNOWN_ACCOUNT"


@pytest.mark.parametrize(
    "batch_id",
    ["", ".hidden", "../escape", "contains space", "A" * 65],
)
def test_write_outputs_rejects_unsafe_batch_ids(
    spark: SparkSession,
    tmp_path: Path,
    batch_id: str,
) -> None:
    frame = spark.createDataFrame([(1,)], ["value"])

    with pytest.raises(ValueError, match="Batch ID must be"):
        write_outputs(
            frame,
            frame,
            date(2026, 8, 1),
            batch_id,
            tmp_path,
        )


def test_pipeline_arguments_parse_documented_command() -> None:
    arguments = _parse_arguments(
        [
            "--processing-date",
            "2026-08-01",
            "--batch-id",
            "PYSPARK-20260801-001",
            "--legacy-batch-id",
            "LEGACY-20260801-001",
            "--output-dir",
            "custom-output",
        ]
    )

    assert arguments.processing_date == date(2026, 8, 1)
    assert arguments.batch_id == "PYSPARK-20260801-001"
    assert arguments.legacy_batch_id == "LEGACY-20260801-001"
    assert arguments.output_dir == Path("custom-output")


@pytest.mark.parametrize(
    ("argument", "value"),
    [
        ("--processing-date", "2026/08/01"),
        ("--batch-id", "../unsafe"),
    ],
)
def test_pipeline_arguments_reject_invalid_values(
    argument: str,
    value: str,
) -> None:
    command = [
        "--processing-date",
        "2026-08-01",
        "--batch-id",
        "PYSPARK-20260801-001",
    ]
    command[command.index(argument) + 1] = value

    with pytest.raises(SystemExit) as error:
        _parse_arguments(command)

    assert error.value.code == 2


def test_pipeline_arguments_allow_skipping_reconciliation() -> None:
    arguments = _parse_arguments(
        [
            "--processing-date",
            "2026-08-01",
            "--batch-id",
            "PYSPARK-20260801-001",
            "--skip-reconciliation",
        ]
    )

    assert arguments.skip_reconciliation
    assert arguments.legacy_batch_id is None
