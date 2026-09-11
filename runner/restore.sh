#!/usr/bin/env bash
#
# Descarga un objeto del almacenamiento y lo restaura en una base PostgreSQL.
#
# Mismo contrato por variables de entorno que backup.sh. El formato no se pasa:
# se deduce del propio volcado, porque un fichero en formato Custom empieza por
# la firma "PGDMP" y uno Plain es SQL en claro. Deducirlo evita que una
# discrepancia entre lo declarado y lo real produzca un error incomprensible.

set -euo pipefail

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require() {
  local nombre="$1"
  if [ -z "${!nombre:-}" ]; then
    fail "falta la variable de entorno $nombre"
  fi
}

for v in PGVAULT_PG_HOST PGVAULT_PG_DATABASE PGVAULT_S3_ENDPOINT \
         PGVAULT_S3_BUCKET PGVAULT_OBJECT_KEY PGUSER PGPASSWORD \
         AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
  require "$v"
done

PGVAULT_PG_PORT="${PGVAULT_PG_PORT:-5432}"
PGVAULT_PG_SSLMODE="${PGVAULT_PG_SSLMODE:-prefer}"
PGVAULT_DROP_EXISTING="${PGVAULT_DROP_EXISTING:-false}"

export PGPASSWORD
export PGSSLMODE="$PGVAULT_PG_SSLMODE"

echo "==> origen  ${PGVAULT_S3_BUCKET}/${PGVAULT_OBJECT_KEY}"
echo "==> destino ${PGVAULT_PG_HOST}:${PGVAULT_PG_PORT}/${PGVAULT_PG_DATABASE}"
echo "==> dropExisting=${PGVAULT_DROP_EXISTING}"

mc alias set origen "$PGVAULT_S3_ENDPOINT" "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY" >/dev/null \
  || fail "no se pudo autenticar contra ${PGVAULT_S3_ENDPOINT}"

# El volcado se materializa en disco en vez de restaurarse en streaming porque
# pg_restore necesita leer la tabla de contenidos del formato Custom antes de
# empezar, y eso exige poder buscar dentro del fichero.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
volcado="${tmp}/dump"

echo "==> descargando..."
mc cat "origen/${PGVAULT_S3_BUCKET}/${PGVAULT_OBJECT_KEY}" > "$volcado"
[ -s "$volcado" ] || fail "el objeto descargado esta vacio"

inicio=$(date +%s)

if head -c 5 "$volcado" | grep -q "PGDMP"; then
  echo "==> formato Custom detectado, usando pg_restore"
  restore_args=(-h "$PGVAULT_PG_HOST" -p "$PGVAULT_PG_PORT" -d "$PGVAULT_PG_DATABASE"
                --no-password --no-owner --no-privileges)
  if [ "$PGVAULT_DROP_EXISTING" = "true" ]; then
    restore_args+=(--clean --if-exists)
  fi
  # pg_restore devuelve codigo distinto de cero por avisos que no son fatales,
  # asi que se inspecciona la salida en vez de confiar solo en el codigo.
  if ! pg_restore "${restore_args[@]}" "$volcado" 2> "${tmp}/err"; then
    if grep -qi "error" "${tmp}/err"; then
      cat "${tmp}/err" >&2
      fail "pg_restore fallo"
    fi
    echo "==> pg_restore termino con avisos no fatales:"
    cat "${tmp}/err"
  fi
else
  echo "==> formato Plain detectado, usando psql"
  psql -h "$PGVAULT_PG_HOST" -p "$PGVAULT_PG_PORT" -d "$PGVAULT_PG_DATABASE" \
       --no-password -v ON_ERROR_STOP=1 -f "$volcado"
fi

fin=$(date +%s)

echo "==> restaurado en $((fin - inicio))s"
echo "PGVAULT_RESULT restored=${PGVAULT_OBJECT_KEY} durationSeconds=$((fin - inicio))"
