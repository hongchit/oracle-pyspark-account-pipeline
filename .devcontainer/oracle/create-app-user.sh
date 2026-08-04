#!/usr/bin/env bash
set -euo pipefail

if [[ ! ${APP_DB_USERNAME:-} =~ ^[A-Z][A-Z0-9_]{0,29}$ ]]; then
  printf '%s\n' \
    'APP_DB_USERNAME must be an uppercase Oracle identifier containing 1-30 letters, digits, or underscores and starting with a letter.' >&2
  exit 1
fi

if [[ ! ${APP_DB_PASSWORD:-} =~ ^[A-Za-z0-9]{16,64}$ ]]; then
  printf '%s\n' \
    'APP_DB_PASSWORD must contain 16-64 ASCII letters or digits.' >&2
  exit 1
fi

sqlplus -s / as sysdba <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;

DECLARE
  tablespace_count PLS_INTEGER;
  user_count PLS_INTEGER;
BEGIN
  SELECT COUNT(*)
    INTO tablespace_count
    FROM dba_tablespaces
   WHERE tablespace_name = 'ACCOUNT_APP_DATA';

  IF tablespace_count = 0 THEN
    EXECUTE IMMEDIATE
      'CREATE TABLESPACE ACCOUNT_APP_DATA '
      || 'DATAFILE ''/opt/oracle/oradata/FREE/FREEPDB1/account_app_data01.dbf'' '
      || 'SIZE 100M AUTOEXTEND ON NEXT 50M MAXSIZE 1G';
  END IF;

  SELECT COUNT(*)
    INTO user_count
    FROM dba_users
   WHERE username = '${APP_DB_USERNAME}'
     AND oracle_maintained = 'N';

  IF user_count = 0 THEN
    EXECUTE IMMEDIATE
      'CREATE USER ${APP_DB_USERNAME} IDENTIFIED BY "${APP_DB_PASSWORD}" '
      || 'DEFAULT TABLESPACE ACCOUNT_APP_DATA QUOTA UNLIMITED ON ACCOUNT_APP_DATA';
  ELSE
    EXECUTE IMMEDIATE
      'ALTER USER ${APP_DB_USERNAME} IDENTIFIED BY "${APP_DB_PASSWORD}" '
      || 'DEFAULT TABLESPACE ACCOUNT_APP_DATA ACCOUNT UNLOCK';
    EXECUTE IMMEDIATE 'ALTER USER ${APP_DB_USERNAME} QUOTA UNLIMITED ON ACCOUNT_APP_DATA';
  END IF;
END;
/

GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE TO ${APP_DB_USERNAME};
EXIT;
SQL
