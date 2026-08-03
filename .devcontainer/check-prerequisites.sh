#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f .env ]]; then
  printf '%s\n' \
    'Missing .env.' \
    'Copy .env.example to .env, set ORACLE_PWD and APP_DB_PASSWORD, then reopen the Dev Container.' >&2
  exit 1
fi

if ! grep -Eq '^APP_DB_PASSWORD=.+$' .env; then
  printf '%s\n' \
    'Missing APP_DB_PASSWORD in .env.' \
    'Add a strong local application-schema password and reopen the Dev Container.' >&2
  exit 1
fi
