#!/usr/bin/env bash
set -euo pipefail

repository_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
processing_date=$(date +%F)
seed=12
cleanup=true
legacy_batch_id=
pyspark_batch_id=
output_dir=data/output
report_dir=reports
pause_enabled=true

usage() {
  cat <<'EOF'
Usage: scripts/run-demo.sh [options]

Options:
  --processing-date YYYY-MM-DD  Demo date (default: current date)
  --seed INTEGER                Deterministic seed (default: 12)
  --cleanup true|false          Replace existing demo data (default: true)
  --legacy-batch-id ID          Legacy batch ID (default: derived from date)
  --pyspark-batch-id ID         PySpark batch ID (default: derived from date)
  --output-dir PATH             Parquet root (default: data/output)
  --report-dir PATH             Report directory (default: reports)
  --no-pause                    Run until the end without pausing at checkpoints
  -h, --help                    Show this help
EOF
}

pause_for_demo() {
  local title=$1
  local guidance=$2
  local response

  if [[ "${pause_enabled}" != true ]]; then
    return
  fi
  printf '\n=== %s ===\n%s\n' "${title}" "${guidance}"
  read -r -p "Press Enter to continue or q to stop: " response
  if [[ "${response}" == q || "${response}" == Q ]]; then
    echo "Demo stopped at presenter checkpoint."
    exit 0
  fi
}

while (($# > 0)); do
  case "$1" in
    --processing-date)
      processing_date=${2:?"missing --processing-date value"}
      shift 2
      ;;
    --seed)
      seed=${2:?"missing --seed value"}
      shift 2
      ;;
    --cleanup)
      cleanup=${2:?"missing --cleanup value"}
      shift 2
      ;;
    --legacy-batch-id)
      legacy_batch_id=${2:?"missing --legacy-batch-id value"}
      shift 2
      ;;
    --pyspark-batch-id)
      pyspark_batch_id=${2:?"missing --pyspark-batch-id value"}
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
    --no-pause)
      pause_enabled=false
      shift
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

if [[ "${pause_enabled}" == true && ! -t 0 ]]; then
  echo "Interactive demo requires a TTY; use --no-pause for automation." >&2
  exit 2
fi

compact_date=${processing_date//-/}
legacy_batch_id=${legacy_batch_id:-LEGACY-${compact_date}-001}
pyspark_batch_id=${pyspark_batch_id:-PYSPARK-${compact_date}-001}

cd "${repository_dir}"
scripts/generate-data.sh \
  --processing-date "${processing_date}" \
  --seed "${seed}" \
  --cleanup "${cleanup}"
pause_for_demo \
  "Source data generated" \
  "Inspect ACCOUNT and TRANSACTION_EVENT with sql/demo_inspection.sql."

scripts/run-legacy.sh \
  --processing-date "${processing_date}" \
  --batch-id "${legacy_batch_id}" \
  --skip-build
pause_for_demo \
  "Legacy batch completed" \
  "Inspect LEGACY_DAILY_BALANCE and LEGACY_REJECTED_TRANSACTION."

scripts/run-pyspark.sh \
  --processing-date "${processing_date}" \
  --pyspark-batch-id "${pyspark_batch_id}" \
  --output-dir "${output_dir}" \
  --report-dir "${report_dir}" \
  --skip-reconciliation
pause_for_demo \
  "PySpark outputs written" \
  "Inspect ${output_dir}/processing_date=${processing_date}/batch_id=${pyspark_batch_id}."

scripts/reconcile.sh \
  --processing-date "${processing_date}" \
  --legacy-batch-id "${legacy_batch_id}" \
  --pyspark-batch-id "${pyspark_batch_id}" \
  --output-dir "${output_dir}" \
  --report-dir "${report_dir}"
pause_for_demo \
  "Reconciliation completed" \
  "Inspect ${report_dir}/reconciliation-summary.json and reconciliation-differences.csv."

echo "Demo workflow completed successfully."
