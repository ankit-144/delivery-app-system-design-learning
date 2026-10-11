#!/bin/bash
# Read replica (Phase 5). On first start, clone the primary with pg_basebackup; -R writes
# standby.signal and primary_conninfo, so Postgres starts as a hot standby that streams WAL.
set -euo pipefail
PGDATA=/var/lib/postgresql/data
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "replica: cloning the primary with pg_basebackup..."
  rm -rf "${PGDATA:?}"/*
  chown postgres:postgres "$PGDATA"
  until gosu postgres pg_basebackup -d "host=postgres port=5432 user=quickdrop password=quickdrop" \
        -D "$PGDATA" -R -X stream -c fast; do
    echo "replica: primary not ready yet, retrying in 2 s"
    sleep 2
  done
fi
chmod 700 "$PGDATA"
exec gosu postgres postgres -D "$PGDATA" \
  -c hot_standby=on \
  -c shared_buffers=128MB \
  -c max_connections=100 \
  -c shared_preload_libraries=pg_stat_statements
