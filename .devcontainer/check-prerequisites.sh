#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f .env ]]; then
  printf '%s\n' \
    'Missing .env.' \
    'Copy .env.example to .env, set ORACLE_PWD, APP_DB_USERNAME, and APP_DB_PASSWORD, then reopen the Dev Container.' >&2
  exit 1
fi

if ! grep -Eq '^APP_DB_USERNAME=[A-Z][A-Z0-9_]{0,29}$' .env; then
  printf '%s\n' \
    'Missing or invalid APP_DB_USERNAME in .env.' \
    'Use an uppercase Oracle identifier containing 1-30 letters, digits, or underscores and starting with a letter.' >&2
  exit 1
fi

if ! grep -Eq '^APP_DB_PASSWORD=.+$' .env; then
  printf '%s\n' \
    'Missing APP_DB_PASSWORD in .env.' \
    'Add a strong local application-schema password and reopen the Dev Container.' >&2
  exit 1
fi
