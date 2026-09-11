package io.pgvault.operator.controller;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.pgvault.operator.api.v1alpha1.Backup;
import io.pgvault.operator.api.v1alpha1.BackupPolicy;
import io.pgvault.operator.api.v1alpha1.BackupPolicySpec;
import io.pgvault.operator.api.v1alpha1.BackupPolicyStatus;
import io.pgvault.operator.api.v1alpha1.BackupSpec;
import io.pgvault.operator.api.v1alpha1.BackupStatus;
import io.pgvault.operator.api.v1alpha1.BackupSummary;
import io.pgvault.operator.api.v1alpha1.ConcurrencyPolicy;
import io.pgvault.operator.api.v1alpha1.ExecutionPhase;
import io.pgvault.operator.api.v1alpha1.LocalObjectRef;
import io.pgvault.operator.api.v1alpha1.RetentionPolicy;
import io.pgvault.operator.retention.BackupRecord;
import io.pgvault.operator.retention.Retention;
import io.pgvault.operator.schedule.CronExpression;
import io.pgvault.operator.schedule.ScheduleWindow;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * El planificador. Convierte una politica en Backups puntuales.
 *
 * <p>El cron vive aqui dentro y no en CronJobs de Kubernetes generados al vuelo.
 * Delegar habria sido menos codigo y bastante menos control: la politica de
 * concurrencia de un CronJob no distingue entre saltar un disparo y aplazarlo,
 * el proximo disparo no se puede consultar sin recalcularlo por fuera, y un
 * cambio de zona horaria no se puede atender hasta el siguiente ciclo. Al
 * reprogramar la reconciliacion para el instante exacto del proximo disparo,
 * este reconciler hace lo mismo que el controlador de CronJob del propio
 * Kubernetes, que es la forma de demostrar que se entendio el bucle en vez de
 * esquivarlo.
 */
@ApplicationScoped
public class BackupPolicyReconciler implements Reconciler<BackupPolicy> {

    private static final Logger LOG = Logger.getLogger(BackupPolicyReconciler.class);

    /** Etiqueta que marca los Backup creados por una politica. */
    public static final String LABEL_POLICY = "pgvault.io/policy";

    /**
     * Sufijo del nombre de los Backup generados. Un disparo cae siempre en un
     * minuto concreto, asi que el nombre identifica al disparo sin ambiguedad y
     * sirve de clave de deduplicacion: si el operator se reinicia justo despues
     * de crear el recurso, el intento siguiente choca con un nombre que ya existe
     * y se sabe que no hay nada que hacer.
     */
    private static final DateTimeFormatter BACKUP_NAME_STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmm");

    /**
     * Margen que se suma al reprogramar. El temporizador puede despertar unos
     * milisegundos antes del disparo, y sin margen esa pasada no encontraria nada
     * que hacer y volveria a dormirse.
     */
    private static final Duration WAKE_UP_MARGIN = Duration.ofSeconds(1);

    /**
     * Informer sobre los Backup, mapeados a su politica por {@code spec.policyRef}.
     *
     * <p>El mapeo va por la referencia que el usuario escribe, no por owner
     * references, porque los Backup de una politica no le pertenecen: ver mas
     * abajo, en la creacion.
     */
    @Override
    public List<EventSource<?, BackupPolicy>> prepareEventSources(
            EventSourceContext<BackupPolicy> context) {

        InformerEventSourceConfiguration<Backup> configuration =
                InformerEventSourceConfiguration.from(Backup.class, BackupPolicy.class)
                        .withSecondaryToPrimaryMapper(backup -> {
                            BackupSpec spec = backup.getSpec();
                            if (spec == null || spec.getPolicyRef() == null) {
                                return Set.of();
                            }
                            return Set.of(new ResourceID(spec.getPolicyRef().getName(),
                                    backup.getMetadata().getNamespace()));
                        })
                        .build();

        return List.of(new InformerEventSource<>(configuration, context));
    }

    @Override
    public UpdateControl<BackupPolicy> reconcile(BackupPolicy policy, Context<BackupPolicy> context) {

        BackupPolicyStatus status = policy.getStatus() != null
                ? policy.getStatus()
                : new BackupPolicyStatus();
        if (status.getConditions() == null) {
            status.setConditions(new ArrayList<>());
        }
        policy.setStatus(status);

        String before = Serialization.asJson(status);
        Long generation = policy.getMetadata().getGeneration();
        BackupPolicySpec spec = policy.getSpec();
        status.setObservedGeneration(generation);

        if (spec == null) {
            Conditions.set(status.getConditions(), Conditions.READY, false, "MissingSpec",
                    "La politica no tiene spec.", generation);
            return patchIfChanged(policy, status, before);
        }

        List<Backup> backups = new ArrayList<>(context.getSecondaryResources(Backup.class));
        List<Backup> active = backups.stream().filter(BackupPolicyReconciler::isActive).toList();
        status.setActiveBackups(active.stream()
                .map(backup -> backup.getMetadata().getName())
                .sorted()
                .toList());
        // Se asigna, no se acumula: si la ultima copia buena se borra, el status
        // tiene que dejar de anunciarla. Un resumen que sobrevive al recurso que
        // resume es una mentira que nadie va a comprobar hasta que la necesite.
        status.setLastSuccessfulBackup(lastSuccessful(backups).orElse(null));

        // El regex del CRD solo filtra lo barato, el numero de campos y los
        // caracteres. Que "0 3 31 2 *" no ocurra jamas o que America/Bogata sea
        // una errata solo lo sabe esto, y por eso el fallo se reporta como
        // condition en vez de rechazarse en el servidor de API.
        ZoneId zone;
        CronExpression cron;
        try {
            zone = ZoneId.of(spec.getTimeZone() != null ? spec.getTimeZone() : "UTC");
            cron = CronExpression.parse(spec.getSchedule());
            // Un cron puede estar bien escrito y no ocurrir jamas, como el 31 de
            // febrero. La unica forma de saberlo es pedirle un disparo, asi que
            // se le pide aqui dentro y no mas abajo: fuera del try, esa misma
            // excepcion saldria del reconciler y la politica se quedaria sin
            // status, que es justo el sitio donde hay que contarlo.
            cron.nextAfter(ZonedDateTime.now(zone));
        } catch (RuntimeException e) {
            status.setNextScheduleTime(null);
            Conditions.set(status.getConditions(), Conditions.READY, false, "InvalidSchedule",
                    e.getMessage(), generation);
            Conditions.set(status.getConditions(), Conditions.SCHEDULED, false, "InvalidSchedule",
                    "No hay nada programado mientras el schedule no se pueda interpretar.", generation);
            LOG.warnf("Politica %s/%s no planificable: %s",
                    policy.getMetadata().getNamespace(), policy.getMetadata().getName(), e.getMessage());
            return patchIfChanged(policy, status, before);
        }

        Conditions.set(status.getConditions(), Conditions.READY, true, "Accepted",
                "Schedule " + cron + " en " + zone + ".", generation);

        if (Boolean.TRUE.equals(spec.getSuspend())) {
            status.setNextScheduleTime(null);
            Conditions.set(status.getConditions(), Conditions.SCHEDULED, false, "Suspended",
                    "La politica esta suspendida. Ni se disparan copias ni se borra ninguna.",
                    generation);
            // Sin reprogramacion: al reanudarla cambia el spec, y un cambio de
            // spec ya despierta al reconciler por si solo.
            return patchIfChanged(policy, status, before);
        }

        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime lastProcessed = parseOr(status.getLastScheduleTime(),
                policy.getMetadata().getCreationTimestamp(), zone);
        Duration deadline = spec.getStartingDeadlineSeconds() != null
                ? Duration.ofSeconds(spec.getStartingDeadlineSeconds())
                : null;

        ScheduleWindow window = ScheduleWindow.evaluate(cron, lastProcessed, now, deadline);

        if (window.expired() != null) {
            status.setLastScheduleTime(window.expired().toInstant().toString());
            Conditions.set(status.getConditions(), Conditions.SCHEDULED, true, "MissedTriggerDropped",
                    "El disparo de " + window.expired() + " llego fuera de su ventana y se descarto. "
                            + "El siguiente es " + window.next() + ".", generation);
            LOG.infof("Politica %s/%s: disparo de %s descartado por startingDeadlineSeconds",
                    policy.getMetadata().getNamespace(), policy.getMetadata().getName(),
                    window.expired());
        } else if (window.due() != null) {
            trigger(policy, status, active, window, generation, context);
        } else {
            Conditions.set(status.getConditions(), Conditions.SCHEDULED, true, "Scheduled",
                    "Proximo disparo " + window.next() + ".", generation);
        }

        status.setNextScheduleTime(window.next().toInstant().toString());

        collectGarbage(policy, backups, zone, context);

        Duration untilNext = Duration.between(Instant.now(), window.next().toInstant())
                .plus(WAKE_UP_MARGIN);
        if (untilNext.isNegative()) {
            untilNext = WAKE_UP_MARGIN;
        }

        UpdateControl<BackupPolicy> control = patchIfChanged(policy, status, before);
        return control.rescheduleAfter(untilNext);
    }

    /**
     * Borra las copias que sobran, al final de cada reconciliacion que llego
     * hasta aqui.
     *
     * <p>Este metodo no decide nada: pregunta y ejecuta. Toda la logica esta en
     * {@link Retention}, que es una funcion pura y por eso se puede probar con
     * una lista escrita a mano en vez de con un cluster y un reloj falso.
     *
     * <p>Y solo borra recursos. El objeto del bucket se lo lleva el finalizer de
     * cada Backup, que es lo que mantiene el almacenamiento como consecuencia del
     * estado de la API en vez de como un segundo inventario que hay que
     * sincronizar. Si alguien borra una copia a mano con kubectl, ocurre
     * exactamente lo mismo que si la descarta la retencion.
     */
    private void collectGarbage(BackupPolicy policy,
                                List<Backup> backups,
                                ZoneId zone,
                                Context<BackupPolicy> context) {

        RetentionPolicy retention = policy.getSpec().getRetention();
        if (retention == null) {
            return;
        }

        Map<String, Backup> byName = new HashMap<>();
        List<BackupRecord> records = new ArrayList<>();

        for (Backup backup : backups) {
            // Una copia en curso no se toca, y una que ya se esta borrando
            // tampoco: su finalizer puede tardar unos segundos en soltarla y
            // durante ese rato seguiria apareciendo aqui.
            if (backup.getMetadata().getDeletionTimestamp() != null) {
                continue;
            }
            BackupStatus backupStatus = backup.getStatus();
            if (backupStatus == null
                    || backupStatus.getCompletionTime() == null
                    || (backupStatus.getPhase() != ExecutionPhase.Succeeded
                        && backupStatus.getPhase() != ExecutionPhase.Failed)) {
                continue;
            }
            try {
                String name = backup.getMetadata().getName();
                records.add(new BackupRecord(name,
                        Instant.parse(backupStatus.getCompletionTime()),
                        backupStatus.getPhase() == ExecutionPhase.Succeeded));
                byName.put(name, backup);
            } catch (RuntimeException e) {
                LOG.debugf("Copia %s con completionTime ilegible, la retencion la ignora",
                        backup.getMetadata().getName());
            }
        }

        for (BackupRecord discarded
                : Retention.selectForDeletion(records, retention, zone, Instant.now())) {
            LOG.infof("Politica %s/%s: la retencion descarta %s, terminada el %s",
                    policy.getMetadata().getNamespace(), policy.getMetadata().getName(),
                    discarded.name(), discarded.completedAt());
            context.getClient().resource(byName.get(discarded.name())).delete();
        }
    }

    /** Aplica la politica de concurrencia y, si procede, crea el Backup del disparo. */
    private void trigger(BackupPolicy policy,
                         BackupPolicyStatus status,
                         List<Backup> active,
                         ScheduleWindow window,
                         Long generation,
                         Context<BackupPolicy> context) {

        String namespace = policy.getMetadata().getNamespace();
        String name = policy.getMetadata().getName();
        ConcurrencyPolicy concurrency = policy.getSpec().getConcurrencyPolicy() != null
                ? policy.getSpec().getConcurrencyPolicy()
                : ConcurrencyPolicy.Forbid;

        // El disparo se da por procesado pase lo que pase, tambien cuando se
        // salta. Un disparo saltado por concurrencia es una decision tomada, no
        // una tarea pendiente: dejarlo sin marcar haria que se ejecutara tarde,
        // pegado al que todavia estaba corriendo, que es justo lo que Forbid
        // intenta evitar.
        status.setLastScheduleTime(window.due().toInstant().toString());

        if (!active.isEmpty() && concurrency == ConcurrencyPolicy.Forbid) {
            Conditions.set(status.getConditions(), Conditions.SCHEDULED, true, "ConcurrencySkipped",
                    "El disparo de " + window.due() + " se salto porque " + active.size()
                            + " copia(s) siguen en curso. El siguiente es " + window.next() + ".",
                    generation);
            LOG.infof("Politica %s/%s: disparo de %s saltado, hay %d copias en curso",
                    namespace, name, window.due(), active.size());
            return;
        }

        if (!active.isEmpty() && concurrency == ConcurrencyPolicy.Replace) {
            // Borrar el Backup en curso dispara su finalizer, que cancela el Job y
            // se lleva por delante el objeto a medio subir. Reemplazar de verdad
            // es eso: no dejar detras una copia truncada que parezca valida.
            for (Backup running : active) {
                LOG.infof("Politica %s/%s: reemplazando la copia en curso %s",
                        namespace, name, running.getMetadata().getName());
                context.getClient().resource(running).delete();
            }
        }

        String backupName = name + "-" + window.due()
                .withZoneSameInstant(ZoneId.of("UTC"))
                .format(BACKUP_NAME_STAMP);

        try {
            context.getClient().resource(backupOf(policy, backupName)).create();
            LOG.infof("Politica %s/%s: creado %s para el disparo de %s",
                    namespace, name, backupName, window.due());
            Conditions.set(status.getConditions(), Conditions.SCHEDULED, true, "Triggered",
                    "Disparo de " + window.due() + " lanzado como " + backupName
                            + ". El siguiente es " + window.next() + ".", generation);
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) {
                throw e;
            }
            // Ya existia: el operator se reinicio entre crear el recurso y
            // anotar el disparo en el status. El nombre deterministico convierte
            // esa carrera en un no-op.
            LOG.debugf("Politica %s/%s: %s ya existia", namespace, name, backupName);
            Conditions.set(status.getConditions(), Conditions.SCHEDULED, true, "Triggered",
                    "Disparo de " + window.due() + " ya estaba lanzado como " + backupName + ".",
                    generation);
        }
    }

    /**
     * Los Backup generados no llevan owner reference a su politica, y es la
     * decision menos obvia de este modulo.
     *
     * <p>Un CronJob si es dueno de sus Jobs, asi que borrarlo se los lleva por
     * delante. Aqui eso significaria que borrar una politica borra todas las
     * copias, y con ellas todos los objetos del bucket, por el finalizer de cada
     * una. Un unico {@code kubectl delete backuppolicy} tirando meses de copias
     * no es una limpieza elegante: es una perdida de datos con buena prensa. La
     * unica via para borrar copias es la retencion del modulo 6, que decide
     * cuales sobran, o un borrado explicito.
     */
    private Backup backupOf(BackupPolicy policy, String backupName) {

        Backup backup = new Backup();
        backup.setMetadata(new ObjectMetaBuilder()
                .withName(backupName)
                .withNamespace(policy.getMetadata().getNamespace())
                .withLabels(Map.of(
                        LABEL_POLICY, policy.getMetadata().getName(),
                        RunnerJobs.LABEL_NAME, "pgvault",
                        RunnerJobs.LABEL_MANAGED_BY, "pgvault-operator"))
                .build());

        LocalObjectRef policyRef = new LocalObjectRef();
        policyRef.setName(policy.getMetadata().getName());

        BackupSpec spec = new BackupSpec();
        spec.setPolicyRef(policyRef);
        backup.setSpec(spec);

        return backup;
    }

    private static boolean isActive(Backup backup) {
        if (backup.getMetadata().getDeletionTimestamp() != null) {
            return false;
        }
        BackupStatus status = backup.getStatus();
        return status == null
                || status.getPhase() == null
                || status.getPhase() == ExecutionPhase.Pending
                || status.getPhase() == ExecutionPhase.Running;
    }

    /**
     * Resumen de la ultima copia correcta, desnormalizado en el status de la
     * politica para que la pregunta de las tres de la manana, cuando fue la
     * ultima copia buena, se conteste con un kubectl get y no listando y
     * ordenando todos los Backup del namespace.
     */
    private static Optional<BackupSummary> lastSuccessful(List<Backup> backups) {
        return backups.stream()
                .filter(backup -> backup.getStatus() != null
                        && backup.getStatus().getPhase() == ExecutionPhase.Succeeded
                        && backup.getStatus().getCompletionTime() != null)
                .max(Comparator.comparing(backup -> backup.getStatus().getCompletionTime()))
                .map(backup -> {
                    BackupSummary summary = new BackupSummary();
                    summary.setName(backup.getMetadata().getName());
                    summary.setCompletionTime(backup.getStatus().getCompletionTime());
                    summary.setSizeBytes(backup.getStatus().getSizeBytes());
                    summary.setObjectKey(backup.getStatus().getObjectKey());
                    return summary;
                });
    }

    private static ZonedDateTime parseOr(String preferred, String fallback, ZoneId zone) {
        for (String value : new String[]{preferred, fallback}) {
            if (value != null) {
                try {
                    return Instant.parse(value).atZone(zone);
                } catch (RuntimeException e) {
                    LOG.debugf("Marca de tiempo ilegible en el status: %s", value);
                }
            }
        }
        return Instant.now().atZone(zone);
    }

    private static UpdateControl<BackupPolicy> patchIfChanged(BackupPolicy policy,
                                                              BackupPolicyStatus status,
                                                              String before) {
        if (before.equals(Serialization.asJson(status))) {
            return UpdateControl.noUpdate();
        }
        return UpdateControl.patchStatus(policy);
    }
}
