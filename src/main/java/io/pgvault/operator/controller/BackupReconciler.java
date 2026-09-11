package io.pgvault.operator.controller;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Workflow;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import io.pgvault.operator.api.v1alpha1.Backup;
import io.pgvault.operator.api.v1alpha1.BackupStatus;
import io.pgvault.operator.api.v1alpha1.Destination;
import io.pgvault.operator.api.v1alpha1.ExecutionPhase;
import io.quarkiverse.operatorsdk.annotations.RBACRule;
import io.quarkiverse.operatorsdk.annotations.RBACVerbs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Un Backup gobierna un Job y refleja su destino.
 *
 * <p>El reconciler no vuelca nada: crea el Job, mira como termina y traduce ese
 * final a status. Esa separacion es la que permite reiniciar el operator en
 * mitad de un volcado de media hora sin perderlo, y la que evita que un backup
 * lento bloquee el bucle de reconciliacion.
 *
 * <p>El workflow se invoca de forma explicita, no automatica. Con invocacion
 * automatica, un Backup ya terminado al que alguien le borrara el Job volveria a
 * crearlo, porque un dependiente declarativo solo sabe que falta algo que
 * deberia estar. Y volver a crearlo significaria volcar otra vez la base y
 * sobrescribir el objeto que ya estaba bien. Una ejecucion terminada es un hecho
 * del pasado: aqui se comprueba la fase antes de tocar nada.
 */
@ApplicationScoped
@Workflow(explicitInvocation = true,
        dependents = @Dependent(type = BackupJobDependentResource.class))
@RBACRule(apiGroups = "", resources = "pods", verbs = {RBACVerbs.GET, RBACVerbs.LIST})
@RBACRule(apiGroups = "pgvault.io", resources = "backuppolicies", verbs = {RBACVerbs.GET, RBACVerbs.LIST})
public class BackupReconciler implements Reconciler<Backup>, Cleaner<Backup> {

    private static final Logger LOG = Logger.getLogger(BackupReconciler.class);

    /** Cada cuanto se vuelve a mirar un Job de limpieza en marcha. */
    private static final Duration CLEANUP_POLL = Duration.ofSeconds(5);

    /** Espera antes de reintentar una limpieza que fallo del todo. */
    private static final Duration CLEANUP_RETRY = Duration.ofSeconds(60);

    /**
     * Red de seguridad por si el operator muere entre que la limpieza termina y
     * el finalizer se suelta. Kubernetes recoge el Job aunque nadie vuelva.
     */
    private static final int CLEANUP_JOB_TTL_SECONDS = 600;

    @Inject
    BackupResolver resolver;

    @Override
    public UpdateControl<Backup> reconcile(Backup backup, Context<Backup> context) {

        BackupStatus status = backup.getStatus() != null ? backup.getStatus() : new BackupStatus();
        if (status.getConditions() == null) {
            status.setConditions(new ArrayList<>());
        }
        backup.setStatus(status);

        if (isTerminal(status.getPhase())) {
            LOG.debugf("%s ya termino en %s, no hay nada que reconciliar",
                    backup.getMetadata().getName(), status.getPhase());
            return UpdateControl.noUpdate();
        }

        // Instantanea del status antes de tocarlo. Comparar el JSON al final
        // evita escribir cuando no ha cambiado nada, y cada escritura evitada es
        // un evento menos y una reconciliacion menos.
        String before = Serialization.asJson(status);
        Long generation = backup.getMetadata().getGeneration();

        ResolvedBackup resolved = resolver.resolve(backup, context.getClient());

        // El Job que ya existe manda sobre lo que diga la politica hoy: es el que
        // sabe con que clave se subio el objeto. Si la politica cambio de prefijo
        // despues de lanzarlo, recalcular la clave apuntaria a un objeto que no
        // existe y el finalizer borraria la nada.
        Optional<Job> existing = context.getSecondaryResource(Job.class);
        String objectKey = firstNonNull(
                status.getObjectKey(),
                existing.map(BackupReconciler::objectKeyOf).orElse(null),
                resolved.objectKey());

        status.setObservedGeneration(generation);
        status.setObjectKey(objectKey);
        status.setJobName(RunnerJobs.backupJobName(backup.getMetadata().getName()));
        if (status.getDestination() == null) {
            status.setDestination(resolved.destination());
            status.setFormat(resolved.format());
            status.setCompression(resolved.compression());
        }

        context.managedWorkflowAndDependentResourceContext()
                .put(ResolvedBackup.CONTEXT_KEY, resolved);
        context.managedWorkflowAndDependentResourceContext().reconcileManagedWorkflow();

        Job job = context.getSecondaryResource(Job.class).orElse(null);
        applyJobOutcome(backup, status, job, context, generation);

        if (before.equals(Serialization.asJson(status))) {
            return UpdateControl.noUpdate();
        }
        return UpdateControl.patchStatus(backup);
    }

    /** Traduce el estado del Job a fase, condiciones, duracion y tamano. */
    private void applyJobOutcome(Backup backup,
                                 BackupStatus status,
                                 Job job,
                                 Context<Backup> context,
                                 Long generation) {

        if (job == null) {
            status.setPhase(ExecutionPhase.Pending);
            Conditions.set(status.getConditions(), Conditions.READY, false, "JobNotCreatedYet",
                    "El Job todavia no existe.", generation);
            return;
        }

        status.setStartTime(Jobs.startTime(job));

        if (Jobs.isRunning(job)) {
            status.setPhase(ExecutionPhase.Running);
            Conditions.set(status.getConditions(), Conditions.READY, false, "JobRunning",
                    "El Job " + job.getMetadata().getName() + " esta en marcha.", generation);
            return;
        }

        RunnerReport report = RunnerReport.lastAttempt(podsOf(backup, context)).orElse(null);

        if (Jobs.isComplete(job)) {
            status.setPhase(ExecutionPhase.Succeeded);
            status.setCompletionTime(Jobs.completionTime(job));
            if (report != null) {
                status.setSizeBytes(report.sizeBytes());
                status.setPostgresVersion(report.postgresVersion());
            }
            status.setDurationSeconds(elapsedSeconds(Jobs.startTime(job),
                    Jobs.completionTime(job), report));
            Conditions.set(status.getConditions(), Conditions.READY, true, "BackupComplete",
                    "La copia esta en " + status.getObjectKey() + ".", generation);
            LOG.infof("Backup %s/%s completado: %s, %s bytes",
                    backup.getMetadata().getNamespace(), backup.getMetadata().getName(),
                    status.getObjectKey(), status.getSizeBytes());
            return;
        }

        status.setPhase(ExecutionPhase.Failed);
        status.setCompletionTime(report != null && report.finishedAt() != null
                ? report.finishedAt().toString()
                : Instant.now().toString());
        status.setDurationSeconds(elapsedSeconds(Jobs.startTime(job), status.getCompletionTime(), report));

        // La causa del fallo la escribe el propio runner en su mensaje de
        // terminacion. Sin eso, el status solo podria decir "BackoffLimitExceeded",
        // que es exactamente lo que ya se ve en el Job y no explica nada.
        String detail = report != null && report.error() != null
                ? report.error()
                : Jobs.failure(job).map(BackupReconciler::describe)
                        .orElse("El Job termino sin exito y sin dejar causa.");

        Conditions.set(status.getConditions(), Conditions.READY, false, "BackupFailed",
                detail, generation);
        LOG.warnf("Backup %s/%s fallido: %s",
                backup.getMetadata().getNamespace(), backup.getMetadata().getName(), detail);
    }

    /**
     * Deja la causa de un error inesperado en el status, no solo en los logs.
     *
     * <p>Quien aplico el YAML no tiene acceso a los logs del operator, y muchas
     * veces tampoco al namespace donde corre. Si la unica pista de que su
     * politica apunta a un Secret que no existe esta en un log ajeno, el operator
     * es opaco por diseno.
     */
    @Override
    public ErrorStatusUpdateControl<Backup> updateErrorStatus(Backup backup,
                                                              Context<Backup> context,
                                                              Exception e) {

        BackupStatus status = backup.getStatus() != null ? backup.getStatus() : new BackupStatus();
        if (status.getConditions() == null) {
            status.setConditions(new ArrayList<>());
        }
        backup.setStatus(status);

        if (status.getPhase() == null) {
            status.setPhase(ExecutionPhase.Pending);
        }

        String reason = e instanceof BackupResolutionException ? "ResolutionFailed" : "ReconcileError";
        Conditions.set(status.getConditions(), Conditions.READY, false, reason,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                backup.getMetadata().getGeneration());

        return ErrorStatusUpdateControl.patchStatus(backup);
    }

    /**
     * Borra el objeto del bucket antes de soltar el finalizer.
     *
     * <p>El borrado lo hace otro Job con la misma imagen runner, no el operator.
     * Asi las credenciales de S3 siguen viviendo solo dentro de un Pod efimero,
     * y el operator no necesita permiso para leer Secret de nadie. El precio es
     * que borrar un Backup tarda unos segundos en vez de un milisegundo, y a
     * cambio se conserva la propiedad que hace fiable a esto: el estado del
     * bucket es siempre una consecuencia del estado de la API.
     *
     * <p>El finalizer no se suelta mientras la limpieza no confirme. Soltarlo
     * ante un fallo convertiria cada error de red en un objeto huerfano que nadie
     * volveria a mirar, y en una factura que crece sola.
     */
    @Override
    public DeleteControl cleanup(Backup backup, Context<Backup> context) {

        String namespace = backup.getMetadata().getNamespace();
        String name = backup.getMetadata().getName();
        KubernetesClient client = context.getClient();
        BackupStatus status = backup.getStatus();

        // Un volcado en marcha tiene que morir primero. Si se borrara el objeto
        // con el Job todavia subiendo, el runner terminaria de escribirlo despues
        // y dejaria exactamente el huerfano que este finalizer existe para evitar.
        Job backupJob = client.batch().v1().jobs()
                .inNamespace(namespace).withName(RunnerJobs.backupJobName(name)).get();
        if (backupJob != null && Jobs.isRunning(backupJob)) {
            LOG.infof("Cancelando el Job de %s/%s antes de limpiar el objeto", namespace, name);
            client.batch().v1().jobs().inNamespace(namespace)
                    .withName(backupJob.getMetadata().getName()).delete();
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(CLEANUP_POLL);
        }

        if (status == null || status.getObjectKey() == null || status.getDestination() == null) {
            LOG.debugf("%s/%s no llego a subir nada, no hay objeto que borrar", namespace, name);
            return DeleteControl.defaultDelete();
        }

        String cleanupName = RunnerJobs.cleanupJobName(name);
        Job cleanupJob = client.batch().v1().jobs()
                .inNamespace(namespace).withName(cleanupName).get();

        if (cleanupJob == null) {
            LOG.infof("Borrando %s del bucket %s", status.getObjectKey(),
                    status.getDestination().getS3().getBucket());
            client.resource(cleanupJob(backup, status)).create();
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(CLEANUP_POLL);
        }

        if (Jobs.isComplete(cleanupJob)) {
            client.batch().v1().jobs().inNamespace(namespace).withName(cleanupName).delete();
            LOG.infof("Objeto %s borrado, soltando el finalizer de %s/%s",
                    status.getObjectKey(), namespace, name);
            return DeleteControl.defaultDelete();
        }

        Optional<JobCondition> failed = Jobs.failure(cleanupJob);
        if (failed.isPresent()) {
            LOG.errorf("No se pudo borrar %s: %s. El Backup %s/%s se queda con el finalizer puesto.",
                    status.getObjectKey(), describe(failed.get()), namespace, name);
            // Se borra el Job agotado para que la proxima pasada cree uno nuevo:
            // un Job que ya gasto su backoffLimit no vuelve a intentarlo solo.
            client.batch().v1().jobs().inNamespace(namespace).withName(cleanupName).delete();
            return DeleteControl.noFinalizerRemoval().rescheduleAfter(CLEANUP_RETRY);
        }

        return DeleteControl.noFinalizerRemoval().rescheduleAfter(CLEANUP_POLL);
    }

    /**
     * El Job de limpieza va sin owner reference a proposito.
     *
     * <p>Colgarlo de un recurso que ya esta en proceso de borrado es pedirle al
     * recolector que lo recoja antes de que haga su trabajo. Lo borra este mismo
     * codigo cuando termina, y el TTL cubre el caso de que el operator no llegue
     * a verlo.
     */
    private Job cleanupJob(Backup backup, BackupStatus status) {

        String name = backup.getMetadata().getName();
        Destination destination = status.getDestination();

        return new JobBuilder()
                .withNewMetadata()
                .withName(RunnerJobs.cleanupJobName(name))
                .withNamespace(backup.getMetadata().getNamespace())
                .withLabels(RunnerJobs.labels(name, RunnerJobs.COMPONENT_CLEANUP))
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(2)
                .withTtlSecondsAfterFinished(CLEANUP_JOB_TTL_SECONDS)
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(RunnerJobs.labels(name, RunnerJobs.COMPONENT_CLEANUP))
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withSecurityContext(RunnerJobs.podSecurityContext())
                .withContainers(RunnerJobs.container(RunnerJobs.COMPONENT_CLEANUP,
                        List.of("/usr/local/bin/cleanup.sh"),
                        RunnerJobs.s3Env(destination, status.getObjectKey())))
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private List<Pod> podsOf(Backup backup, Context<Backup> context) {
        return context.getClient().pods()
                .inNamespace(backup.getMetadata().getNamespace())
                .withLabel(RunnerJobs.LABEL_BACKUP, backup.getMetadata().getName())
                .withLabel(RunnerJobs.LABEL_COMPONENT, RunnerJobs.COMPONENT_BACKUP)
                .list()
                .getItems();
    }

    private static String objectKeyOf(Job job) {
        if (job.getSpec() == null || job.getSpec().getTemplate() == null) {
            return null;
        }
        return job.getSpec().getTemplate().getSpec().getContainers().stream()
                .findFirst()
                .map(c -> RunnerJobs.envValue(c, "PGVAULT_OBJECT_KEY"))
                .orElse(null);
    }

    private static Long elapsedSeconds(String start, String end, RunnerReport report) {
        try {
            if (start != null && end != null) {
                return Duration.between(Instant.parse(start), Instant.parse(end)).toSeconds();
            }
        } catch (RuntimeException e) {
            LOG.debugf("Marcas de tiempo del Job ilegibles: %s a %s", start, end);
        }
        return report != null ? report.durationSeconds() : null;
    }

    private static String describe(JobCondition condition) {
        String reason = condition.getReason() != null ? condition.getReason() : "Failed";
        return condition.getMessage() != null ? reason + ": " + condition.getMessage() : reason;
    }

    private static boolean isTerminal(ExecutionPhase phase) {
        return phase == ExecutionPhase.Succeeded || phase == ExecutionPhase.Failed;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
