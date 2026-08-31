from datetime import date
from typing import Any

import pytest

from account_migration import OracleJdbcConfig, read_source_tables


class FakeDataFrame:
    def __init__(self, columns: list[str]) -> None:
        self.columns = columns

    def toDF(self, *columns: str) -> "FakeDataFrame":  # noqa: N802
        return FakeDataFrame(list(columns))


class FakeDataFrameReader:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def jdbc(
        self,
        *,
        url: str,
        table: str,
        properties: dict[str, str],
    ) -> FakeDataFrame:
        self.calls.append(
            {"url": url, "table": table, "properties": properties.copy()}
        )
        if table == "ACCOUNT":
            return FakeDataFrame(["ACCOUNT_ID", "OPENING_BALANCE"])
        return FakeDataFrame(["EVENT_ID", "POSTING_DATE", "AMOUNT"])


class FakeSparkSession:
    def __init__(self) -> None:
        self.read = FakeDataFrameReader()


def test_oracle_jdbc_config_from_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = {
        "ORACLE_HOST": "oracle",
        "ORACLE_PORT": "1521",
        "ORACLE_SERVICE": "FREEPDB1",
        "APP_DB_USERNAME": "ACCOUNT_APP",
        "APP_DB_PASSWORD": "test-password",
    }
    for name, value in settings.items():
        monkeypatch.setenv(name, value)

    config = OracleJdbcConfig.from_environment()

    assert config.jdbc_url == "jdbc:oracle:thin:@//oracle:1521/FREEPDB1"
    assert config.username == "ACCOUNT_APP"
    assert config.password == "test-password"


def test_oracle_jdbc_config_reports_all_missing_variables(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    for name in (
        "ORACLE_HOST",
        "ORACLE_PORT",
        "ORACLE_SERVICE",
        "APP_DB_USERNAME",
        "APP_DB_PASSWORD",
    ):
        monkeypatch.delenv(name, raising=False)

    with pytest.raises(ValueError) as error:
        OracleJdbcConfig.from_environment()

    message = str(error.value)
    assert "ORACLE_HOST" in message
    assert "ORACLE_PORT" in message
    assert "ORACLE_SERVICE" in message
    assert "APP_DB_USERNAME" in message
    assert "APP_DB_PASSWORD" in message


def test_reader_pushes_down_date_and_lowercases_columns() -> None:
    spark = FakeSparkSession()
    config = OracleJdbcConfig(
        host="oracle",
        port="1521",
        service="FREEPDB1",
        username="ACCOUNT_APP",
        password="test-password",
    )

    tables = read_source_tables(spark, date(2026, 8, 1), config)  # type: ignore[arg-type]

    assert tables.accounts.columns == ["account_id", "opening_balance"]
    assert tables.transactions.columns == [
        "event_id",
        "posting_date",
        "amount",
    ]
    assert len(spark.read.calls) == 2

    account_call, transaction_call = spark.read.calls
    assert account_call == {
        "url": "jdbc:oracle:thin:@//oracle:1521/FREEPDB1",
        "table": "ACCOUNT",
        "properties": {
            "driver": "oracle.jdbc.OracleDriver",
            "user": "ACCOUNT_APP",
            "password": "test-password",
        },
    }
    assert transaction_call["url"] == account_call["url"]
    assert transaction_call["properties"] == account_call["properties"]
    assert "FROM TRANSACTION_EVENT" in transaction_call["table"]
    assert "POSTING_DATE >= DATE '2026-08-01'" in transaction_call["table"]
    assert "POSTING_DATE < DATE '2026-08-02'" in transaction_call["table"]
