# Imagen que ejecuta el volcado y la restauracion.
#
# El operator nunca corre pg_dump dentro de su propio proceso: crea un Job con
# esta imagen y observa como termina. Eso es lo que permite reiniciar el
# operator a mitad de un backup sin perderlo, y lo que hace que un volcado de
# media hora no bloquee el bucle de reconciliacion.
#
# La version de pg_dump debe ser igual o mayor que la del servidor de origen.
# Volcar un PostgreSQL 17 con un pg_dump 16 falla, y falla tarde.
FROM postgres:16-alpine

# mc trae subida en streaming desde stdin, que es lo que evita materializar el
# volcado en disco. bash se instala por set -o pipefail: sin el, un pg_dump que
# muere a mitad deja que mc suba un objeto truncado y el backup se da por bueno.
# Para una herramienta de copias ese es el peor fallo posible.
RUN apk add --no-cache bash zstd

COPY --from=minio/mc:latest /usr/bin/mc /usr/local/bin/mc

COPY backup.sh restore.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore.sh

# UID numerico, no "postgres", y esto no es cosmetico: con runAsNonRoot en el
# pod, kubelet tiene que poder comprobar que el usuario no es root ANTES de
# arrancar el contenedor, y para eso necesita un numero. Con un nombre falla con
# "cannot verify user is non-root" y el pod se queda en CreateContainerConfigError.
# 70 es el uid del usuario postgres en la imagen base.
USER 70:70

ENTRYPOINT ["/usr/local/bin/backup.sh"]
