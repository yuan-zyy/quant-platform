#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || ! $1 =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]]; then
  printf 'Usage: %s DISPOSABLE_POSTGRES_CONTAINER [VERIFY_COMMAND ...]\n' "$0" >&2
  exit 2
fi
container=$1
shift
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
migrations="$script_dir/../../quant-server/src/main/resources/db/migration"
source_db=quant_recovery_source
target_db=quant_recovery_target
pg_user=${PGUSER:-postgres}
source_created=0
target_created=0

pg() {
  local command=$1
  shift
  docker exec -i -e PGHOST=/var/run/postgresql -e PGPORT=5432 -e PGPASSWORD "$container" \
    "$command" --username="$pg_user" "$@"
}

cleanup() {
  local status=$?
  trap - EXIT
  if (( target_created )); then
    pg dropdb --force "$target_db" || status=1
  fi
  if (( source_created )); then
    pg dropdb --force "$source_db" || status=1
  fi
  exit "$status"
}
trap cleanup EXIT

# createdb refuses an existing name; the cleanup owns only databases it successfully created.
pg createdb --maintenance-db=postgres "$source_db"
source_created=1
pg createdb --maintenance-db=postgres "$target_db"
target_created=1

for migration in "$migrations"/V001__backtest_tasks.sql "$migrations"/V002__broker_orders.sql \
  "$migrations"/V003__broker_order_reconciliation.sql; do
  pg psql --no-psqlrc --set=ON_ERROR_STOP=1 --dbname="$source_db" < "$migration"
done
pg psql --no-psqlrc --set=ON_ERROR_STOP=1 --dbname="$source_db" < "$script_dir/seed.sql"
pg pg_dump --format=custom --no-owner --no-privileges --dbname="$source_db" \
  | pg pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --dbname="$target_db"
pg psql --no-psqlrc --set=ON_ERROR_STOP=1 --dbname="$target_db" < "$script_dir/verify.sql"

if (( $# )); then
  QUANT_RECOVERY_RESTORED_DB="$target_db" "$@"
fi
printf 'PostgreSQL recovery drill passed.\n'
