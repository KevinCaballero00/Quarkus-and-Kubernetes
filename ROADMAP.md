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
    credentialsSecretRef: { name: orders-db }
  destination:
    s3:
      endpoint: http://minio.pgvault-system.svc:9000
      bucket: pg-backups
      prefix: orders/
      forcePathStyle: true
      credentialsSecretRef: { name: minio-creds }
  format: custom                     # plain | custom | directory
  compression: zstd
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
status:
  phase: Succeeded                   # Pending | Running | Succeeded | Failed
  objectKey: orders/2026-09-10T030000Z.dump.zst
  sizeBytes: 418533376
  durationSeconds: 74
  checksumSHA256: 9f2c...

---
# ------------------------------------------ recuperación, la escribes tú
apiVersion: pgvault.io/v1alpha1
kind: Restore
spec:
  backupRef: { name: orders-nightly-20260910-0300 }
  target:
    host: postgres-staging.apps.svc.cluster.local
    database: orders
    dropExisting: true
```

---

## El plan: once módulos, en orden

Cada módulo depende del anterior y termina en algo que puedes demostrar. No pases al siguiente sin
cumplir el criterio de cierre: es lo que evita acumular deuda invisible.

### M0 · Entorno local — medio día

Un clúster real donde equivocarte sin consecuencias.

- [ ] Arrancar Docker Desktop y confirmar que el daemon responde.
- [ ] Instalar `kind`, `helm`, el CLI de Quarkus y `gh` con winget o scoop.
- [ ] Crear un clúster `pgvault-dev` de un control-plane y dos workers.
- [ ] Crear el repo en GitHub desde el día cero y comprometerte a commits pequeños. El historial también se mira.

**Cierras cuando:** los tres nodos aparecen Ready y el repo tiene su primer commit.

### M1 · Esqueleto Quarkus y primer ciclo — 1 día

Cerrar el circuito completo con un CRD de juguete, antes de que la lógica de negocio complique el diagnóstico.

- [ ] Generar el proyecto con la extensión `quarkus-operator-sdk` y fijar versiones exactas.
- [ ] Un CRD trivial y un reconciler que solo escriba una marca de tiempo en el status.
- [ ] Modo dev con recarga en caliente apuntando al clúster kind.
- [ ] Entender dónde deja el build los CRDs generados y el RBAC.

**Cierras cuando:** aplicas un CR y `kubectl get` muestra el status que escribió tu código.

### M2 · Diseño de la API — 2 días

Los tres CRDs completos. Aquí es donde un operator se distingue de un script disfrazado.

- [ ] Validación declarativa con las anotaciones de Fabric8: campos requeridos, patrón de cron, rangos numéricos.
- [ ] Reglas CEL en el esquema para lo que las anotaciones no cubren, como exigir exactamente uno entre referencia a política y configuración en línea.
- [ ] Columnas de impresión para que `kubectl get` sea legible sin describir nada.
- [ ] Subrecurso de status con condiciones al estilo estándar de Kubernetes, incluyendo motivo, mensaje y generación observada.

**Cierras cuando:** el servidor de API rechaza por sí solo un cron mal escrito, sin que tu código intervenga.

### M3 · Plano de datos — 1 a 2 días

Que el volcado funcione a mano antes de automatizarlo. Depurar un Job es mucho más barato que depurar
un Job que además creó un reconciler.

- [ ] Desplegar PostgreSQL con datos de ejemplo y MinIO en el clúster.
- [ ] Construir la imagen runner: cliente de PostgreSQL más un cargador a S3, con volcado en streaming para no llenar el disco del contenedor.
- [ ] Ejecutarla como un Job escrito a mano y recuperar el volcado.

**Cierras cuando:** un Job manual deja un objeto en el bucket y lo restauras en una base vacía.

### M4 · Reconciler de Backup — 2 a 3 días

El corazón del proyecto. Un CR de `Backup` gobierna un Job y refleja su destino.

- [ ] El Job como recurso dependiente, con un matcher que nunca intente actualizarlo: los Jobs son casi inmutables y este es el error clásico que provoca bucles de reconciliación.
- [ ] Fuente de eventos sobre Jobs, mapeando cada uno a su `Backup` por referencia de propietario.
- [ ] Traducir el estado del Job a fase, condiciones, duración, tamaño y clave del objeto.
- [ ] Finalizer que borra el objeto en S3 cuando se borra el CR, sin soltar el finalizer si el borrado falla.
- [ ] Manejador de error que deja la causa en el status, no solo en los logs.

**Cierras cuando:** un `Backup` ad-hoc llega a Succeeded con su clave, y borrarlo con kubectl libera el objeto en MinIO.

### M5 · Reconciler de política y planificador — 2 días

El cron vive dentro del operator, no en CronJobs de Kubernetes. Es más trabajo y es la decisión que
más vas a defender.

- [ ] Calcular el próximo disparo respetando la zona horaria y reprogramar la reconciliación exactamente para ese momento.
- [ ] Registrar último y próximo disparo en el status.
- [ ] Honrar la suspensión y la política de concurrencia.
- [ ] Ventana de disparos perdidos: qué hacer si el operator estuvo caído dos horas.

**Cierras cuando:** una política cada dos minutos genera Backups puntuales, y suspenderla los detiene sin borrar nada.

### M6 · Retención — 1 a 2 días

Sin esto el proyecto es una demo. Con esto es una herramienta.

- [ ] Escribirlo como una función pura: lista de backups más política, devuelve la lista a borrar. Sin clientes ni relojes dentro.
- [ ] Combinar los criterios de últimos N, diarios, semanales y edad máxima sin que se pisen.
- [ ] Recolectar al final de cada reconciliación exitosa de la política.

**Cierras cuando:** con conservar los últimos tres y disparos rápidos, nunca hay un cuarto CR ni un cuarto objeto.

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
