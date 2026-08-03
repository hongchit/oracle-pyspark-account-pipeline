#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f .env ]]; then
  printf '%s\n' \
    'Missing .env.' \
    'Copy .env.example to .env, set ORACLE_PWD, and reopen the Dev Container.' >&2
  exit 1
fi

