#!/usr/bin/env bash
set -euo pipefail

repository_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
processing_date=$(date +%F)
seed=12
cleanup=true
build=true

usage() {
  cat <<'EOF'
Usage: scripts/generate-data.sh [options]

Options:
  --processing-date YYYY-MM-DD  Source posting date (default: current date)
  --seed INTEGER                Deterministic seed (default: 12)
  --cleanup true|false          Replace existing demo data (default: true)
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
    --seed)
      seed=${2:?"missing --seed value"}
      shift 2
      ;;
    --cleanup)
      cleanup=${2:?"missing --cleanup value"}
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

cd "${repository_dir}"
if [[ "${build}" == true ]]; then
  mvn --batch-mode --file legacy-app/pom.xml package
fi

java -jar legacy-app/target/legacy-app-0.1.0-SNAPSHOT.jar generate-data \
  "--processing-date=${processing_date}" \
  "--seed=${seed}" \
  "--cleanup=${cleanup}"
