#!/usr/bin/env bash
set -euo pipefail

repository_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
processing_date=$(date +%F)
pyspark_batch_id=
legacy_batch_id=
output_dir=data/output
report_dir=reports

usage() {
  cat <<'EOF'
Usage: scripts/reconcile.sh [options]

Options:
  --processing-date YYYY-MM-DD  Balance date (default: current date)
  --pyspark-batch-id ID         PySpark batch ID (default: derived from date)
  --legacy-batch-id ID          Legacy reference ID (default: derived from date)
  --output-dir PATH             Parquet root (default: data/output)
  --report-dir PATH             Report directory (default: reports)
  -h, --help                    Show this help
EOF
}

while (($# > 0)); do
  case "$1" in
    --processing-date)
      processing_date=${2:?"missing --processing-date value"}
      shift 2
      ;;
    --pyspark-batch-id)
      pyspark_batch_id=${2:?"missing --pyspark-batch-id value"}
      shift 2
      ;;
    --legacy-batch-id)
      legacy_batch_id=${2:?"missing --legacy-batch-id value"}
      shift 2
      ;;
    --output-dir)
      output_dir=${2:?"missing --output-dir value"}
      shift 2
      ;;
    --report-dir)
      report_dir=${2:?"missing --report-dir value"}
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

compact_date=${processing_date//-/}
pyspark_batch_id=${pyspark_batch_id:-PYSPARK-${compact_date}-001}
legacy_batch_id=${legacy_batch_id:-LEGACY-${compact_date}-001}

cd "${repository_dir}"
uv run --frozen python -m account_migration.reconcile \
  --processing-date "${processing_date}" \
  --legacy-batch-id "${legacy_batch_id}" \
  --pyspark-batch-id "${pyspark_batch_id}" \
  --output-dir "${output_dir}" \
  --report-dir "${report_dir}"
