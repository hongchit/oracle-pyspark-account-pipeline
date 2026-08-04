#!/usr/bin/env bash
set -euo pipefail

workspace_dir=/workspaces/oracle-pyspark-account-pipeline

cd "${workspace_dir}"

uv run --frozen ruff check .
uv run --frozen ruff format --check .
mvn --batch-mode --file legacy-app/pom.xml validate
