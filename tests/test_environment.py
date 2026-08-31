import os
import sys

import oracledb
import pyspark
import pytest
from pyspark.sql import SparkSession


def test_python_version() -> None:
    assert sys.version_info[:2] == (3, 12)


def test_pyspark_version() -> None:
    assert pyspark.__version__ == "4.2.0"


@pytest.fixture(scope="session")
def spark() -> SparkSession:
    session = (
        SparkSession.builder.master("local[2]")
        .appName("oracle-pyspark-environment-tests")
        .config("spark.ui.enabled", "false")
        .getOrCreate()
    )
    yield session
    session.stop()


def test_pyspark_local_calculation(spark: SparkSession) -> None:
    result = spark.range(10).groupBy().sum("id").first()[0]
    assert result == 45


def _oracle_settings() -> tuple[str, str, str, str]:
    return (
        os.environ["ORACLE_HOST"],
        os.environ["ORACLE_PORT"],
        os.environ["ORACLE_SERVICE"],
        os.environ["ORACLE_PWD"],
    )


def test_oracle_python_connectivity() -> None:
    host, port, service, password = _oracle_settings()
    with oracledb.connect(
        user="system",
        password=password,
        dsn=f"{host}:{port}/{service}",
    ) as connection:
        with connection.cursor() as cursor:
            assert cursor.execute("SELECT 1 FROM dual").fetchone() == (1,)


def test_oracle_spark_jdbc_connectivity(spark: SparkSession) -> None:
    host, port, service, password = _oracle_settings()
    result = (
        spark.read.format("jdbc")
        .option("url", f"jdbc:oracle:thin:@//{host}:{port}/{service}")
        .option("driver", "oracle.jdbc.OracleDriver")
        .option("user", "system")
        .option("password", password)
        .option("dbtable", "(SELECT 1 AS VALUE FROM dual) reference_value")
        .load()
        .first()[0]
    )
    assert result == 1
