"""Read source account data from Oracle with PySpark JDBC."""

import os
from dataclasses import dataclass
from datetime import date, timedelta

from pyspark.sql import DataFrame, SparkSession

_REQUIRED_ENVIRONMENT_VARIABLES = (
    "ORACLE_HOST",
    "ORACLE_PORT",
    "ORACLE_SERVICE",
    "APP_DB_USERNAME",
    "APP_DB_PASSWORD",
)


@dataclass(frozen=True)
class OracleJdbcConfig:
    """Connection settings for the dedicated Oracle application schema."""

    host: str
    port: str
    service: str
    username: str
    password: str
    driver: str = "oracle.jdbc.OracleDriver"

    @classmethod
    def from_environment(cls) -> "OracleJdbcConfig":
        """Load Oracle JDBC settings from the Dev Container environment."""
        values: dict[str, str] = {}
        missing: list[str] = []

        for name in _REQUIRED_ENVIRONMENT_VARIABLES:
            value = os.environ.get(name)
            if value is None or not value.strip():
                missing.append(name)
            else:
                values[name] = value

        if missing:
            missing_names = ", ".join(missing)
            message = "Missing required Oracle JDBC environment variables"
            raise ValueError(f"{message}: {missing_names}")

        return cls(
            host=values["ORACLE_HOST"],
            port=values["ORACLE_PORT"],
            service=values["ORACLE_SERVICE"],
            username=values["APP_DB_USERNAME"],
            password=values["APP_DB_PASSWORD"],
        )

    @property
    def jdbc_url(self) -> str:
        """Return the Oracle Thin JDBC URL."""
        return f"jdbc:oracle:thin:@//{self.host}:{self.port}/{self.service}"


@dataclass(frozen=True)
class SourceTables:
    """Source DataFrames required by the PySpark balance pipeline."""

    accounts: DataFrame
    transactions: DataFrame


def read_source_tables(
    spark: SparkSession,
    processing_date: date,
    config: OracleJdbcConfig | None = None,
) -> SourceTables:
    """Read source tables from Oracle for one processing date."""
    jdbc_config = config or OracleJdbcConfig.from_environment()
    accounts = _read_table(spark, jdbc_config, "ACCOUNT")
    transactions = _read_table(
        spark,
        jdbc_config,
        _transaction_query(processing_date),
    )

    return SourceTables(
        accounts=_lowercase_columns(accounts),
        transactions=_lowercase_columns(transactions),
    )


def _read_table(
    spark: SparkSession,
    config: OracleJdbcConfig,
    table: str,
) -> DataFrame:
    return spark.read.jdbc(
        url=config.jdbc_url,
        table=table,
        properties={
            "driver": config.driver,
            "user": config.username,
            "password": config.password,
        },
    )


def _transaction_query(processing_date: date) -> str:
    next_date = processing_date + timedelta(days=1)
    # A half-open range includes Oracle DATE values with a time component.
    return f"""(
        SELECT *
          FROM TRANSACTION_EVENT
         WHERE POSTING_DATE >= DATE '{processing_date.isoformat()}'
           AND POSTING_DATE < DATE '{next_date.isoformat()}'
    ) transaction_event_source"""


def _lowercase_columns(dataframe: DataFrame) -> DataFrame:
    # Normalize at the boundary so later transforms use Python-style names.
    return dataframe.toDF(*(column.lower() for column in dataframe.columns))
