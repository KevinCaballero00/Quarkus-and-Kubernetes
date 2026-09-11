#!/usr/bin/env bash
#
# Vuelca una base PostgreSQL y la sube en streaming a un almacenamiento S3.
#
# El contrato son variables de entorno, no argumentos, porque en el modulo 4 es
# el operator quien construye el Job y resulta mas facil de leer y de auditar un
# bloque env que una linea de comando armada por concatenacion.
#
# PGVAULT_OBJECT_KEY la calcula quien invoca, no este script. Es deliberado: el
# operator tiene que conocer la clave ANTES de lanzar el Job, porque si el Job
# falla a medias el finalizer necesita saber que objeto borrar. Si la clave la
# inventara el runner, un fallo dejaria basura que nadie sabria localizar.
#
# El resultado se devuelve por el mensaje de terminacion del contenedor, no por
# el log. El kubelet copia ese fichero al status del Pod, asi que el operator lo
# lee con un GET normal y no necesita permiso sobre pods/log, que dejaria ver
# todo lo que cualquier contenedor haya impreso. Ademas los logs se rotan; el
# status del Pod no.

set -euo pipefail

PGVAULT_TERMINATION_LOG="${PGVAULT_TERMINATION_LOG:-/dev/termination-log}"

# El mensaje son pares clave=valor separados por espacios, con error= al final
# porque su valor lleva espacios. El kubelet lo trunca a 4 KiB, de sobra.
report() {
  printf '%s' "$*" > "$PGVAULT_TERMINATION_LOG" 2>/dev/null || true
}

fail() {
  echo "ERROR: $*" >&2
  report "error=$*"
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
PGVAULT_FORMAT="${PGVAULT_FORMAT:-Custom}"
PGVAULT_COMPRESSION="${PGVAULT_COMPRESSION:-Zstd}"

export PGPASSWORD
export PGSSLMODE="$PGVAULT_PG_SSLMODE"

psql_base=(psql -h "$PGVAULT_PG_HOST" -p "$PGVAULT_PG_PORT" -d "$PGVAULT_PG_DATABASE")

echo "==> origen   ${PGVAULT_PG_HOST}:${PGVAULT_PG_PORT}/${PGVAULT_PG_DATABASE}"
echo "==> destino  ${PGVAULT_S3_BUCKET}/${PGVAULT_OBJECT_KEY}"
echo "==> formato  ${PGVAULT_FORMAT}, compresion ${PGVAULT_COMPRESSION}"

server_version="$("${psql_base[@]}" -tAc 'show server_version')" \
  || fail "no se pudo conectar a PostgreSQL"
echo "==> servidor PostgreSQL ${server_version}"

# --- construccion de los argumentos de pg_dump -------------------------------
#
# En formato Custom la compresion la hace pg_dump internamente. Encadenar un
# compresor externo despues seria comprimir datos ya comprimidos: cuesta CPU y
# no gana casi nada. En formato Plain, en cambio, la salida es SQL en claro y
# ahi si tiene sentido la tuberia.

dump_args=(-h "$PGVAULT_PG_HOST" -p "$PGVAULT_PG_PORT" -d "$PGVAULT_PG_DATABASE" --no-password)
compresor=(cat)

case "$PGVAULT_FORMAT" in
  Custom)
    dump_args+=(--format=custom)
    case "$PGVAULT_COMPRESSION" in
      None) dump_args+=(--compress=0) ;;
      Gzip) dump_args+=(--compress=gzip:6) ;;
      Zstd) dump_args+=(--compress=zstd:3) ;;
      *)    fail "compresion no soportada: $PGVAULT_COMPRESSION" ;;
    esac
    ;;
  Plain)
    dump_args+=(--format=plain)
    case "$PGVAULT_COMPRESSION" in
      None) compresor=(cat) ;;
      Gzip) compresor=(gzip -6 -c) ;;
      Zstd) compresor=(zstd -3 -c) ;;
      *)    fail "compresion no soportada: $PGVAULT_COMPRESSION" ;;
    esac
    ;;
  Directory)
    # El formato Directory escribe varios ficheros en disco y no puede
    # transmitirse por una tuberia. Soportarlo exige un volumen temporal y
    # subida por lotes. Se rechaza de forma explicita en vez de fallar raro.
    fail "el formato Directory todavia no esta soportado por este runner"
    ;;
  *)
    fail "formato no soportado: $PGVAULT_FORMAT"
    ;;
esac

# --- destino ------------------------------------------------------------------

mc alias set destino "$PGVAULT_S3_ENDPOINT" "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY" >/dev/null \
  || fail "no se pudo autenticar contra ${PGVAULT_S3_ENDPOINT}"

destino="destino/${PGVAULT_S3_BUCKET}/${PGVAULT_OBJECT_KEY}"

# --- volcado ------------------------------------------------------------------
#
# pipefail hace que el conjunto falle si falla pg_dump aunque mc termine bien.
# Sin esto, una base que se cae a mitad produce un objeto truncado y un Job en
# verde, que es justo el escenario que arruina una recuperacion.

echo "==> volcando..."
inicio=$(date +%s)

pg_dump "${dump_args[@]}" | "${compresor[@]}" | mc pipe "$destino"

fin=$(date +%s)

# --- verificacion -------------------------------------------------------------
#
# El objeto se consulta despues de subirlo en vez de contar bytes al vuelo. Asi
# el tamano que se reporta es el que el almacenamiento dice tener, no el que
# nosotros creemos haber enviado.

tamano="$(mc stat --json "$destino" | sed -n 's/.*"size":\([0-9]*\).*/\1/p')"
[ -n "$tamano" ] || fail "el objeto no aparece en el destino despues de subirlo"
[ "$tamano" -gt 0 ] || fail "el objeto subido esta vacio"

echo "==> listo en $((fin - inicio))s, ${tamano} bytes"

report "objectKey=${PGVAULT_OBJECT_KEY} sizeBytes=${tamano} durationSeconds=$((fin - inicio)) postgresVersion=${server_version}"
