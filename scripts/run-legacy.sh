#!/usr/bin/env bash
set -euo pipefail

repository_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
processing_date=$(date +%F)
batch_id=
build=true

usage() {
  cat <<'EOF'
Usage: scripts/run-legacy.sh [options]

Options:
  --processing-date YYYY-MM-DD  Balance date (default: current date)
  --batch-id ID                 Legacy batch ID (default: derived from date)
  --skip-build                  Use the existing legacy-app JAR
  -h, --help                    Show this help
EOF
}

while (($# > 0)); do
  case "$1" in
    --processing-date)
      processing_date=${2:?"missing --processing-date value"}
      shift 2
      ;;
    --batch-id)
      batch_id=${2:?"missing --batch-id value"}
      shift 2
      ;;
    --skip-build)
      build=false
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

compact_date=${processing_date//-/}
batch_id=${batch_id:-LEGACY-${compact_date}-001}

cd "${repository_dir}"
if [[ "${build}" == true ]]; then
  mvn --batch-mode --file legacy-app/pom.xml package
fi

java -jar legacy-app/target/legacy-app-0.1.0-SNAPSHOT.jar run-legacy-batch \
  "--processing-date=${processing_date}" \
  "--batch-id=${batch_id}"
