# pgvault

**Operator de Kubernetes en Java para backup y restore declarativo de PostgreSQL.**

Escribes una política en YAML; el operator la mantiene cierta.

Stack: Quarkus · Java Operator SDK · Fabric8 · GraalVM native · Helm · kind · MinIO

---

## Estado del entorno

Medido en este equipo el 2026-09-10. Lo que falta se resuelve entero en el módulo 0.

| Herramienta | Versión | Estado |
|---|---|---|
| java | 21.0.11 Temurin LTS | listo |
| maven | 3.9.11 | listo |
| kubectl | 1.36.1 | listo |
| git | 2.51.0 | listo |
| docker | CLI 29.7.2, daemon caído | arrancar |
| kind | sin instalar | falta |
| helm | sin instalar | falta |
| quarkus cli | sin instalar | falta |
| gh | sin instalar | falta |
| contexto k8s | ninguno configurado | falta |

---

## Arquitectura

El operator nunca ejecuta `pg_dump` dentro de su propio proceso. Crea Jobs y observa cómo terminan,
que es lo que lo hace reiniciable y escalable.

```
BackupPolicy  ──►  pgvault operator  ──►  Backup  ──►  Job  ──►  bucket S3
(tú escribes)      (reconcilia y se       (un CR por    (pg_dump      (MinIO en local,
                    reprograma sola)       ejecución)    comprimido)   cualquier S3 en prod)
```

El detalle que hace elegante el diseño: la retención **solo borra CRs de tipo `Backup`**. Un finalizer
en cada `Backup` se encarga de borrar su objeto en el bucket. Nadie sincroniza dos inventarios a mano,
y borrar un `Backup` con `kubectl` libera almacenamiento de verdad.

---

## La API que vas a diseñar

Objetivo del módulo 2. Ponerlo por escrito antes de programar evita rediseñar CRDs a mitad de camino.

```yaml
# ------------------------------------------------------ política
apiVersion: pgvault.io/v1alpha1
kind: BackupPolicy
metadata:
  name: orders-nightly
spec:
  suspend: false
  schedule: "0 3 * * *"
  timeZone: America/Bogota
  concurrencyPolicy: Forbid          # Allow | Forbid | Replace
  source:
    host: postgres.apps.svc.cluster.local
    database: orders
    sslMode: Prefer                    # Disable|Prefer|Require|VerifyCa|VerifyFull
    credentialsSecretRef: { name: orders-db }
  destination:
    s3:
      endpoint: http://minio.pgvault-system.svc:9000
      bucket: pg-backups
      prefix: orders/
      forcePathStyle: true
      credentialsSecretRef: { name: minio-creds }
  format: Custom                       # Plain | Custom | Directory
  compression: Zstd                    # None | Gzip | Zstd
  retention:
    keepLast: 7
    keepWeekly: 4
    maxAge: 30d
status:
  observedGeneration: 4
  lastScheduleTime: "2026-09-10T08:00:00Z"
  nextScheduleTime: "2026-09-11T08:00:00Z"
  conditions: [ Ready, Scheduled, StorageReachable ]

---
# ------------------------------- una ejecución, creada por el operator
apiVersion: pgvault.io/v1alpha1
kind: Backup
spec:
  policyRef: { name: orders-nightly }  # o source + destination, nunca ambos
status:
  phase: Succeeded                     # Pending | Running | Succeeded | Failed
  objectKey: orders/2026-09-10T030000Z.dump.zst
  sizeBytes: 418533376
  durationSeconds: 74
  checksumSHA256: 9f2c...

---
# ------------------------------------------ recuperación, la escribes tú
apiVersion: pgvault.io/v1alpha1
kind: Restore
spec:
  fromPolicy:                          # o backupRef, nunca ambos
    policyRef: { name: orders-nightly }
    mode: Latest                       # Latest | Before (Before exige 'before')
  target:
    host: postgres-staging.apps.svc.cluster.local
    database: orders
    credentialsSecretRef: { name: staging-db }
    dropExisting: true
```

---

## El plan: once módulos, en orden

Cada módulo depende del anterior y termina en algo que puedes demostrar. No pases al siguiente sin
cumplir el criterio de cierre: es lo que evita acumular deuda invisible.

### M0 · Entorno local — medio día

Un clúster real donde equivocarte sin consecuencias.

- [x] Arrancar Docker Desktop y confirmar que el daemon responde. Servidor 29.7.2.
- [x] Instalar `kind` 0.33.0, `helm` 4.3.0 y `gh` 2.100.0 con winget.
- [x] Crear un clúster `pgvault-dev` de un control-plane y dos workers, Kubernetes 1.37.0.
- [x] Repo git local en `main` con los primeros commits.
- [ ] Publicarlo en GitHub. Requiere `gh auth login`, que es interactivo.

**Cierras cuando:** los tres nodos aparecen Ready y el repo tiene su primer commit.

> **El CLI de Quarkus no está en winget.** No hace falta: el proyecto se genera con
> `quarkus-maven-plugin` desde Maven, que además obliga a fijar la versión de la plataforma
> de forma explícita en el `pom.xml`. Mejor para reproducibilidad que un CLI que se
> autoactualiza.

### M1 · Esqueleto Quarkus y primer ciclo — 1 día

Cerrar el circuito completo con un CRD de juguete, antes de que la lógica de negocio complique el diagnóstico.

- [x] Proyecto generado con `quarkus-maven-plugin` sobre la plataforma 3.39.3.
- [x] CRD `BackupPolicy` con `schedule` obligatorio y subrecurso de status.
- [x] Reconciler que sella `observedAt` y `observedGeneration`.
- [x] Verificado en el clúster: el status se escribe y `observedGeneration` sigue al spec.
- [ ] Modo dev con recarga en caliente. La verificación se hizo con el jar empaquetado.

**Cierras cuando:** aplicas un CR y `kubectl get` muestra el status que escribió tu código.

> **La versión del Operator SDK la gobierna la plataforma.** Viaja como BOM miembro
> (`quarkus-operator-sdk-bom`) en la misma 3.39.3 que el core, así que no se fija a mano
> y la compatibilidad queda garantizada.
>
> **`io.fabric8:generator-annotations` hay que declararlo.** Llega al classpath de build
> pero no al de compilación, así que sin declararlo no compilan `@Required`, `@Pattern`
> ni las columnas de impresión que hacen falta en M2. La versión sí la gestiona el BOM.
>
> **Dos avisos de RBAC en el build son esperados.** El generador no sabe en qué namespace
> vivirá la cuenta de servicio del operator. Se resuelven en M8 al fijar el namespace y
> acotar el alcance de observación.

### M2 · Diseño de la API — 2 días

Los tres CRDs completos. Aquí es donde un operator se distingue de un script disfrazado.

- [x] Tres CRDs completos: `BackupPolicy`, `Backup` y `Restore`.
- [x] Validación declarativa: requeridos, patrón de cron, rangos de puerto, nombre de bucket.
- [x] Cuatro reglas CEL, todas verificadas contra el servidor de API.
- [x] Columnas de impresión en los tres recursos.
- [x] Status con `Condition` estándar de Kubernetes y `observedGeneration`.

**Cierras cuando:** el servidor de API rechaza por sí solo un cron mal escrito, sin que tu código intervenga.

> **Los defaults del esquema y CEL se estorban.** El servidor de API aplica los valores por
> defecto antes de evaluar CEL, así que un campo con default hace que `has(self.campo)` sea
> siempre cierto y rompe cualquier regla de exclusividad sobre él. Por eso `format` y
> `compression` no llevan default en `Backup`: los resuelve el reconciler.
>
> **`@Default("")` genera `default: null`.** No es un valor válido para un campo de tipo
> string. Un campo ausente ya significa lo mismo, así que se deja sin default.
>
> **El generador ordena las columnas por su jsonPath**, no por orden de declaración. Deja
> `AGE` primero y `PHASE` en medio. Es cosmético. Si molesta, se corrige al empaquetar los
> CRD en el chart de M10.
>
> **El Operator SDK filtra por cambio de generación.** Anotar o etiquetar un recurso no
> dispara reconciliación, solo la disparan los cambios de spec. Conviene saberlo antes de
> depurar "por qué no reconcilia".

### M3 · Plano de datos — 1 a 2 días

Que el volcado funcione a mano antes de automatizarlo. Depurar un Job es mucho más barato que depurar
un Job que además creó un reconciler.

- [x] Dos PostgreSQL y un MinIO en el namespace `pgvault-system`, con 50.000 filas de ejemplo.
- [x] Imagen runner con `pg_dump`, `pg_restore` y `mc`, volcado en streaming sin tocar disco.
- [x] Backup manual: 50.000 filas comprimidas a 261 KB en el bucket.
- [x] Restauración manual a la instancia de staging con huella MD5 idéntica.

**Cierras cuando:** un Job manual deja un objeto en el bucket y lo restauras en una base vacía.

> **La compresión la hace `pg_dump`, no una tubería.** En formato `Custom` la salida ya va
> comprimida, así que encadenar un compresor externo gastaría CPU sobre datos ya
> comprimidos. El `pg_dump` de esta imagen está enlazado contra libzstd, liblz4 y libz, así
> que `--compress=zstd:3` funciona. La tubería externa solo se usa en formato `Plain`.
>
> **`set -o pipefail` es la garantía de corrección del runner.** Sin él, un `pg_dump` que
> muere a mitad deja que el cargador suba un objeto truncado y el Job termine en verde. Para
> una herramienta de copias ese es el peor fallo posible: se descubre al restaurar.
>
> **La clave del objeto la calcula el operator, no el runner.** Si el Job falla a medias, el
> finalizer necesita saber qué objeto borrar. Una clave inventada dentro del contenedor
> dejaría basura que nadie sabría localizar.
>
> **`runAsNonRoot` exige un UID numérico en la imagen.** Con `USER postgres` el pod se queda
> en `CreateContainerConfigError`, porque kubelet no puede comprobar que no sea root antes de
> arrancar. Hay que escribir `USER 70:70`.
>
> **`kind load` falla con las imágenes de Docker Desktop.** Importa para todas las
> plataformas y Docker solo guarda la local. Con imágenes públicas no hace falta: los nodos
> las descargan. Solo la imagen propia necesita cargarse.

### M4 · Reconciler de Backup — 2 a 3 días

El corazón del proyecto. Un CR de `Backup` gobierna un Job y refleja su destino.

- [x] El Job como recurso dependiente que solo crea y nunca actualiza.
- [x] Fuente de eventos sobre Jobs, mapeando cada uno a su `Backup` por referencia de propietario.
- [x] Estado del Job traducido a fase, condiciones, duración, tamaño y clave del objeto.
- [x] Finalizer que borra el objeto en S3 con otro Job, y no lo suelta si el borrado falla.
- [x] Manejador de error que deja la causa en el status, no solo en los logs.
- [ ] Checksum SHA-256 del objeto. El campo existe en el status; calcularlo en streaming desde bash pide una tubería con `tee` cuya condición de carrera no compensa. Sale gratis en el extra 1, cuando el runner sea un binario.

**Cierras cuando:** un `Backup` ad-hoc llega a Succeeded con su clave, y borrarlo con kubectl libera el objeto en MinIO.

Verificado de extremo a extremo: 267 KB en `orders/orders-m4-20260911T194635Z.dump.zst` en seis segundos,
con la versión del servidor de origen en el status; `kubectl delete` lanzó la limpieza y el objeto
desapareció del bucket en cinco segundos.

> **El dependiente solo crea, y esa es toda la defensa contra el bucle.** La clase implementa
> `Creator` y no `Updater`, así que el SDK ni siquiera llega a comparar el Job deseado con el
> que existe. Con un dependiente normal, la comparación encuentra diferencias en campos que el
> servidor de API no deja cambiar, el parche se rechaza, se reintenta y las vuelve a encontrar.
> Un Job es un hecho consumado: o se deja como está, o se borra y se crea otro.
>
> **El workflow se invoca a mano.** Con invocación automática, borrar el Job de un `Backup` ya
> terminado hace que el dependiente lo recree, porque desde su punto de vista falta algo que
> debería estar. Y recrearlo significa volcar la base otra vez y sobrescribir un objeto que
> estaba bien. Comprobar la fase antes de tocar nada cuesta tres líneas y lo evita.
>
> **El resultado viaja en el mensaje de terminación del contenedor, no en el log.** El runner
> escribe una línea en `/dev/termination-log` y el kubelet la copia al status del Pod, así que
> el operator la lee con un GET normal. Leer el log del Pod exigiría permiso sobre `pods/log`,
> que deja ver todo lo que cualquier contenedor haya impreso, credenciales incluidas, y además
> los logs se rotan: el dato del que depende el status sería el más frágil de todos.
>
> **El borrado en S3 también lo hace un Job.** La alternativa era meter un cliente de S3 en el
> operator, y con él la necesidad de leer los Secret de todos los namespaces observados. Con un
> Job, las credenciales siguen viviendo solo dentro de un Pod efímero y el operator nunca sabe
> más que el nombre del Secret. El precio es que borrar un `Backup` tarda cinco segundos en vez
> de uno; la propiedad que se compra es que el operator no es un objetivo interesante.
>
> **El script de limpieza es idempotente, y no por elegancia.** El finalizer no suelta el
> recurso hasta que la limpieza confirma, así que el mismo borrado puede ejecutarse varias
> veces: un reintento tras un corte, o un Job recreado porque el operator se reinició. Un objeto
> que ya no está significa trabajo hecho, no error. Es lo que hace que un `Backup` fallido, que
> tiene clave pero nunca llegó a subir nada, se borre igual de limpio.
>
> **El `Backup` guarda una copia de su destino en el status.** Sin ella, borrar la política antes
> que sus copias dejaría al finalizer sin saber a qué bucket conectarse y el recurso se quedaría
> colgado para siempre con el objeto huérfano dentro. Con ella, un `Backup` es autosuficiente:
> lleva encima todo lo necesario para localizar, verificar y borrar su propia copia.
>
> **La clave del objeto sale de datos que ya no cambian**, el nombre del recurso y su instante de
> creación, para que recalcularla tras un reinicio devuelva la misma cadena. Y si el Job ya
> existe, manda la clave que lleva en su propio `env`: la política pudo cambiar de prefijo
> después de lanzarlo, y el objeto que se subió no se mueve solo.
>
> **Un `policyRef` que no resuelve se lanza como excepción, no se traga.** Eso da reintentos con
> backoff, que es lo correcto cuando la política llega cinco segundos más tarde. El precio es una
> traza completa en el log del operator ante un error que es del usuario. Se paga a gusto: la
> causa acaba escrita en la condition, que es donde la va a buscar quien aplicó el YAML.

### M5 · Reconciler de política y planificador — 2 días

El cron vive dentro del operator, no en CronJobs de Kubernetes. Es más trabajo y es la decisión que
más vas a defender.

- [x] Parser de cron propio, con el próximo disparo y el anterior, sin dependencias y sin estado.
- [x] Reprogramación de la reconciliación para el instante exacto del próximo disparo.
- [x] Último y próximo disparo, copias en curso y resumen de la última buena, en el status.
- [x] Suspensión y las tres políticas de concurrencia.
- [x] Ventana de disparos perdidos: un operator caído dos horas arranca una copia, no sesenta.

**Cierras cuando:** una política cada dos minutos genera Backups puntuales, y suspenderla los detiene sin borrar nada.

Verificado en el clúster. Una política `*/2 * * * *` en `America/Bogota` disparó a las 20:20:01 y a las
20:22:01 UTC, un segundo después de la hora exacta, que es el margen con el que se reprograma.
Suspenderla dejó pasar el disparo de las 20:24 sin crear nada y sin tocar los dos objetos del bucket.
Al reanudarla apareció una sola copia de recuperación, la del último disparo perdido. Con un origen
inalcanzable y `Forbid`, los disparos de las 20:28 y las 20:29 quedaron saltados con el motivo escrito
en el status mientras la copia de las 20:27 seguía reintentando. La aritmética del cron se comprobó
aparte con `jshell` contra `target/classes`: hora local y zona, regla de Vixie, fin de mes, el 31 de
febrero y la hora que no existe el día del cambio de horario.

> **El parser de cron es propio, y son ciento cincuenta líneas bien gastadas.** La alternativa era
> arrastrar `cron-utils` para leer cinco campos separados por espacios. El patrón del CRD ya
> restringe la entrada a `*`, números, rangos, listas y pasos, así que una dependencia serviría
> sobre todo para entender sintaxis que el servidor de API rechaza antes de llegar aquí. A cambio,
> el cálculo queda como una función pura sin estado: se le pregunta por el próximo disparo y por el
> anterior, y responde sin clientes, sin relojes escondidos y sin un clúster delante.
>
> **Preguntar por el disparo anterior es lo que resuelve la caída de dos horas.** En vez de
> enumerar todo lo que se perdió, se pregunta cuál fue el último disparo y se compara con el que la
> política dice haber procesado. Sale gratis la propiedad que importa: al volver se ejecuta como
> mucho una copia. Nadie quiere sesenta volcados simultáneos contra la misma base a modo de
> bienvenida, y cincuenta y nueve de esas copias quedarían obsoletas en cuanto terminara la última.
> `startingDeadlineSeconds` decide además si ese disparo pendiente todavía merece la pena.
>
> **La búsqueda es en hora local y la conversión a instante se hace al final.** Es lo único que
> respeta de verdad una zona horaria: una política a las tres de la mañana en Bogotá sigue siendo a
> las tres de la mañana aunque cambie el desfase con UTC. Para la hora que no existe el día del
> cambio de horario se adelanta al otro lado del salto, y para la que ocurre dos veces se toma la
> primera. Un disparo movido una hora es mejor que un disparo perdido.
>
> **Un disparo saltado por concurrencia se marca como procesado.** Es la diferencia con el
> controlador de CronJob de Kubernetes, que lo deja pendiente y acaba ejecutándolo tarde, pegado al
> que todavía estaba corriendo. Para copias de seguridad eso es lo contrario de lo que pide
> `Forbid`: la ventana se saltó, y se saltó del todo.
>
> **Los Backup generados no llevan owner reference a su política.** Un CronJob sí es dueño de sus
> Jobs, así que borrarlo se los lleva por delante. Aquí eso significaría que un
> `kubectl delete backuppolicy` borra todas las copias y, por el finalizer de cada una, todos los
> objetos del bucket. Eso no es una limpieza elegante: es una pérdida de datos con buena prensa. Las
> copias solo se borran por retención o a mano.
>
> **El nombre del Backup es la clave de deduplicación.** Se compone del nombre de la política y del
> minuto del disparo en UTC, así que si el operator se reinicia justo después de crear el recurso y
> antes de anotarlo en el status, el intento siguiente choca con un nombre que ya existe y esa
> carrera se convierte en un no-op. La colisión es la respuesta, no el problema.
>
> **Un cron puede estar bien escrito y no ocurrir jamás.** `0 3 31 2 *` es el 31 de febrero: pasa el
> regex del CRD y no se cumple en ninguna fecha del calendario. La única forma de detectarlo es
> pedirle un disparo, y hay que pedírselo dentro del bloque que captura el error. La primera versión
> lo pedía más abajo, la excepción salía del reconciler y la política se quedaba sin status, que es
> justo el sitio donde había que contarlo.
>
> **`kubectl` no imprime las columnas de tipo DATE: imprime la antigüedad.** Y la antigüedad de algo
> que aún no ha pasado es negativa, así que la columna del próximo disparo salía como `<invalid>`.
> DATE sirve para lo que ya ocurrió. Es un error de diseño del módulo 2 que solo se ve cuando hay
> una fecha futura que enseñar.

### M6 · Retención — 1 a 2 días

Sin esto el proyecto es una demo. Con esto es una herramienta.

- [x] Función pura: lista de copias más política, devuelve la lista a borrar. Sin clientes ni relojes dentro.
- [x] Últimos N, diarios, semanales, mensuales y edad máxima, combinados sin pisarse.
- [x] Recolección al final de cada reconciliación de la política que llega hasta el final.

**Cierras cuando:** con conservar los últimos tres y disparos rápidos, nunca hay un cuarto CR ni un cuarto objeto.

Verificado en el clúster con una política cada minuto y `keepLast: 3`. Durante siete minutos y seis
copias, la cuenta se quedó clavada en tres recursos y tres objetos, y los tres siempre eran los tres
últimos. Las reglas se comprobaron aparte con `jshell`: keepLast, keepDaily con dos copias el mismo
día, keepWeekly cruzando semana ISO, maxAge ganándole a un keepLast alto, la última copia
sobreviviendo a un maxAge de una hora, y el último fallo conservándose mientras el anterior se
descarta.

> **La retención solo borra CRs.** El objeto del bucket se lo lleva el finalizer de cada copia, que ya
> existía desde el módulo 4. Es la mitad que completa la idea: el almacenamiento es una consecuencia
> del estado de la API y no un segundo inventario que alguien tenga que sincronizar. Borrar una copia
> a mano con `kubectl` hace exactamente lo mismo que descartarla por política, y eso es justo lo que
> se quiere de una herramienta de copias.
>
> **Los criterios de conservación se suman y `maxAge` resta.** Una copia sobrevive si la salva
> cualquiera de los cuatro `keep*`, así que un `keepLast` bajo no se lleva por delante las semanales.
> `maxAge` es distinto: su descripción dice "descarta lo más viejo que esto", y eso no admite
> excepciones, así que se aplica al final y gana a los demás. Es el único que puede dejar en nada un
> `keepMonthly: 12`, y conviene saberlo antes de escribir los dos juntos.
>
> **La copia correcta más reciente no se borra nunca.** Ni siquiera cuando `maxAge` dice que ya es
> vieja. Una herramienta de copias que se queda sin ninguna copia por una regla mal escrita ha
> fallado en lo único que tenía que hacer. Tiene una consecuencia que hay que decir en voz alta: si
> lo que se busca es un techo legal de retención y no una política de espacio, esa última copia hay
> que borrarla a mano.
>
> **Los periodos que se cuentan son los que tienen copias, no los del calendario.** Con copias
> diarias y una semana de clúster parado, `keepDaily: 7` conserva siete copias y no las tres que
> quedaron dentro de los últimos siete días naturales. Es lo que hace restic, y es lo que espera
> quien escribió el número.
>
> **El día empieza donde dice la política, no en UTC.** Agrupar por día en UTC parte las copias
> nocturnas de media Europa y de toda América por la mitad del día equivocado, y hace que
> `keepDaily` conserve dos copias de una noche y ninguna de otra.
>
> **De las ejecuciones fallidas se conserva la última.** Es la que explica por qué no hay una copia
> más reciente; borrarla dejaría el problema invisible y el status de la política mintiendo por
> omisión. Las anteriores a esa no cuentan nada que esta no cuente ya. Es la misma elección que hace
> Kubernetes con `failedJobsHistoryLimit`, con el límite fijado en uno.
>
> **La función pura no recibe recursos de Kubernetes, sino tres campos por copia.** Nombre, instante
> y si sirvió. Así el algoritmo no puede mirar nada más aunque quiera, y se prueba con una lista
> escrita a mano en vez de con un clúster y un reloj falso. Es la misma razón por la que el cálculo
> del cron vive aparte en el módulo 5.

### M7 · Reconciler de Restore — 2 días

La mitad que casi nadie implementa, y la única que demuestra que los backups sirven.

- [ ] Referencia directa a un `Backup`, o a una política con selector del más reciente o del anterior a una fecha.
- [ ] Job de restauración, con borrado previo de la base como opción explícita.
- [ ] Guardas: rechazar la restauración si el `Backup` no terminó bien.

**Cierras cuando:** borras una tabla, aplicas un `Restore` y los datos vuelven.

### M8 · Endurecimiento — 2 a 3 días

La diferencia entre un ejercicio y algo que alguien instalaría.

- [ ] RBAC mínimo y cuenta de servicio propia, sin permisos de comodín.
- [ ] Elección de líder con Lease, para correr con dos réplicas sin duplicar backups.
- [ ] Sondas de vivacidad y disponibilidad.
- [ ] Alcance de observación configurable: un namespace, varios o todo el clúster.
- [ ] Eventos de Kubernetes en los momentos que importan, que es donde mira un operador humano.
- [ ] Métricas Prometheus: duración, tamaño, fallos y marca de tiempo del último backup exitoso.
- [ ] Una regla de alerta que dispare si el último backup es más viejo que dos intervalos, más un tablero de Grafana.

**Cierras cuando:** con dos réplicas solo una reconcilia, y el endpoint de métricas expone las series.

### M9 · Pruebas — 2 a 3 días

Tres niveles, cada uno atrapando lo que el anterior no puede.

- [ ] Unitarias sobre el cálculo del próximo disparo y el algoritmo de retención, que ya son funciones puras gracias a M5 y M6.
- [ ] De controlador contra un servidor de API simulado, para los caminos de error sin levantar un clúster.
- [ ] Extremo a extremo en kind: política cada minuto, verificar el objeto en el bucket, restaurar y comparar los datos.

**Cierras cuando:** la verificación completa pasa en local y en integración continua desde cero.

### M10 · Empaque y vitrina — 3 a 4 días

El módulo que convierte el trabajo en algo que un reclutador entiende en dos minutos.

- [ ] Imagen nativa con GraalVM, que es el argumento de Quarkus: arranque en milisegundos y decenas de megabytes de memoria frente a un operator en JVM.
- [ ] Chart de Helm con los CRDs separados y valores para imagen, alcance y recursos.
- [ ] GitHub Actions: verificación, compilación nativa, extremo a extremo en kind, publicación de imagen y de chart al etiquetar.
- [ ] README con diagrama, tabla de referencia de los CRDs, arranque rápido en cinco comandos y un GIF de la demo.
- [ ] Una sección corta explicando por qué esto es un operator y no un CronJob con un script. Es la pregunta que te van a hacer.

**Cierras cuando:** alguien clona el repo, sigue el arranque rápido y tiene backups corriendo sin preguntarte nada.

---

## Ritmo: cuatro semanas

| Semana | Módulos | Qué queda hecho |
|---|---|---|
| 1 | M0 – M3 | Entorno, esqueleto, API y volcado funcionando a mano. |
| 2 | M4 – M6 | El operator ya hace backups solo y limpia los viejos. |
| 3 | M7 – M9 | Restauración, endurecimiento y las tres capas de pruebas. |
| 4 | M10 + extras | Nativo, chart, CI y el README que vende el proyecto. |

Los módulos 4 y 5 son los que más se atascan. Si vas a perder tiempo, piérdelo ahí y recórtalo de los extras.

---

## Tres decisiones que tendrás que defender

Un proyecto de portafolio se juzga por las decisiones, no por las líneas.

**El planificador vive en el operator, no en CronJobs generados.**
Delegar en CronJobs de Kubernetes es más fácil, pero pierdes el control del estado. No puedes expresar
la política de concurrencia con la semántica que quieres, ni reaccionar a un cambio de zona horaria, ni
saber cuándo es el próximo disparo sin recalcularlo fuera. Reprogramar la reconciliación al instante
exacto es el mismo patrón que usa el controlador de CronJob de Kubernetes, y demuestra que entendiste
el bucle en vez de esquivarlo.

**La retención borra CRs y el finalizer borra objetos.**
La alternativa es que la retención hable directamente con S3, y entonces tienes dos inventarios que se
desincronizan en cuanto alguien borra un CR a mano. Con un finalizer por `Backup`, el estado del bucket
es siempre una consecuencia del estado de la API. Es la propiedad que hace fiable a un operator.

**El operator no gestiona PostgreSQL.**
Apunta a una base que ya existe, venga de CloudNativePG, de un StatefulSet o de un servicio administrado.
Acotar el alcance así no es una limitación: es lo que hace que la herramienta sea instalable junto a lo
que la gente ya tiene, y evita competir con proyectos de años.

---

## Extras, solo si sobra tiempo

Ordenados por relación entre esfuerzo e impacto. Ninguno es necesario para que el proyecto esté completo.

1. Reemplazar el runner de shell por un CLI de Quarkus compilado a nativo, con volcado y subida en streaming en un solo binario. Coherencia total de stack y una imagen mucho más pequeña.
2. Webhook de admisión para validar lo que ni las anotaciones ni CEL alcanzan, como comprobar que las credenciales referenciadas existen.
3. Recuperación a un punto en el tiempo mediante archivado de WAL. Es un salto grande de complejidad, y también el techo del proyecto.
4. Bundle de OLM y envío a OperatorHub. Poco código y mucha señal en un portafolio.
5. Plugin de kubectl distribuido por krew, para disparar backups y restauraciones sin escribir YAML.
