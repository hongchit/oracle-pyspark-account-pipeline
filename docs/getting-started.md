# Getting Started

## Prerequisites

- Docker Desktop with Dev Container support;
- VS Code and the Dev Containers extension;
- an Oracle Container Registry account with the Database Free terms accepted.

Authenticate Docker before the first build:

```bash
docker login container-registry.oracle.com
```

In order to authenticate, a free Oracle Account is needed. Generate an Auth Token from the Oracle Container Registry portal and use the token to proceed on the docker login above.

## Local configuration

Copy the environment template and replace every placeholder:

```bash
cp .env.example .env
```

`ORACLE_PWD` initializes local Oracle administration accounts.
`APP_DB_USERNAME` and `APP_DB_PASSWORD` protect the dedicated application
schema. Registry credentials are used only by `docker login`; they do not
belong in `.env`.

Never commit `.env` or print expanded Compose configuration containing its
values.

## Dev Container

Open the repository in VS Code and choose **Dev Containers: Reopen in
Container**. The environment starts:

- `app`, containing Python 3.12, uv, PySpark, Java 17, and Maven;
- `oracle`, reachable from `app` as `oracle:1521/FREEPDB1`.

VS Code notebooks use the registered project kernel. No Jupyter server is
started.

Note: The first time starting the repositry takes time to download the Oracle Database Free edition. The first time starting up the database may take over 15 mins.

## Common commands

Run commands inside the Dev Container:

```bash
uv sync --frozen
uv run --frozen pytest
bash scripts/validate-code.sh
mvn --file legacy-app/pom.xml test
```

The language-specific checks are:

```bash
uv run --frozen ruff check .
uv run --frozen ruff format --check .
mvn --batch-mode --file legacy-app/pom.xml validate
```

Python follows the configured PEP 8/Ruff rules. Java follows Google Java Style
through Maven Spotless and Checkstyle.

## Individual workflow commands

Every wrapper supports `--help`:

```bash
scripts/generate-data.sh --help
scripts/run-legacy.sh --help
scripts/run-pyspark.sh --help
scripts/reconcile.sh --help
scripts/run-demo.sh --help
```

The wrappers use the container's current date by default, along with seed `12`
and date-derived batch IDs. Pass `--processing-date` to reproduce a run for a
specific date.
The generator and complete demo enable cleanup by default, which replaces the
existing local demonstration dataset.

## Persistence and cleanup

Stopping the Compose project retains Oracle data and dependency caches.
Removing Compose volumes permanently deletes the local database and caches.
Use volume deletion only when a complete reset is intended.

Generated Parquet and reconciliation reports are ignored by Git. Rerun the
same date and PySpark batch ID to replace only that Parquet run.
