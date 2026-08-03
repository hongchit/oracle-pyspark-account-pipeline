#!/usr/bin/env bash
set -euo pipefail

workspace_dir=/workspaces/oracle-pyspark-account-pipeline
kernel_name=oracle-pyspark-account-pipeline

sudo chown vscode:vscode \
  /home/vscode/.cache \
  /home/vscode/.cache/uv \
  /home/vscode/.ivy2 \
  /home/vscode/.m2

cd "${workspace_dir}"

if [[ ! -f uv.lock ]]; then
  uv lock
fi

uv sync --frozen
uv run --frozen python -m ipykernel install \
  --user \
  --name "${kernel_name}" \
  --display-name "Python 3.12 (Oracle PySpark Account Pipeline)"

mvn --batch-mode --file legacy-app/pom.xml dependency:go-offline
