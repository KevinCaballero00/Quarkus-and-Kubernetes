#!/usr/bin/env bash
#
# Borra un objeto del almacenamiento S3.
#
# Lo lanza el finalizer del Backup cuando alguien borra el CR. Que el borrado lo
# haga un Job y no el propio operator es lo que mantiene las credenciales de S3
# dentro de un Pod efimero: el operator sabe como se llama el Secret, nunca lo
# que hay dentro.
#
# La idempotencia no es un detalle. El finalizer no suelta el recurso hasta que
# este script termina bien, asi que puede ejecutarse varias veces sobre el mismo
# objeto: un reintento tras un corte de red, o un Job recreado porque el operator
# se reinicio. Un objeto que ya no esta significa trabajo hecho, no error.

set -euo pipefail

PGVAULT_TERMINATION_LOG="${PGVAULT_TERMINATION_LOG:-/dev/termination-log}"

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

for v in PGVAULT_S3_ENDPOINT PGVAULT_S3_BUCKET PGVAULT_OBJECT_KEY \
         AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
  require "$v"
done

mc alias set destino "$PGVAULT_S3_ENDPOINT" "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY" >/dev/null \
  || fail "no se pudo autenticar contra ${PGVAULT_S3_ENDPOINT}"

destino="destino/${PGVAULT_S3_BUCKET}/${PGVAULT_OBJECT_KEY}"

if ! mc stat "$destino" >/dev/null 2>&1; then
  echo "==> ${PGVAULT_OBJECT_KEY} ya no esta en el bucket, nada que borrar"
  report "objectKey=${PGVAULT_OBJECT_KEY} deleted=absent"
  exit 0
fi

mc rm "$destino" >/dev/null || fail "no se pudo borrar ${PGVAULT_OBJECT_KEY}"

# Se comprueba despues de borrar en vez de confiar en el codigo de salida. Un
# finalizer que se suelta por un borrado que no ocurrio deja un objeto que ya
# nadie relaciona con ningun recurso.
if mc stat "$destino" >/dev/null 2>&1; then
  fail "${PGVAULT_OBJECT_KEY} sigue en el bucket despues de borrarlo"
fi

echo "==> ${PGVAULT_OBJECT_KEY} borrado de ${PGVAULT_S3_BUCKET}"
report "objectKey=${PGVAULT_OBJECT_KEY} deleted=true"
