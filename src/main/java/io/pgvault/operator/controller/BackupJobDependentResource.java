package io.pgvault.operator.controller;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.GarbageCollected;
import io.javaoperatorsdk.operator.processing.dependent.Creator;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.BooleanWithUndefined;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResource;
import io.pgvault.operator.api.v1alpha1.Backup;

import java.util.ArrayList;
import java.util.List;

/**
 * El Job que ejecuta el volcado, gobernado como recurso dependiente del Backup.
 *
 * <p><b>Solo crea, nunca actualiza.</b> La clase implementa {@link Creator} y no
 * {@code Updater}, asi que el SDK ni siquiera llega a comparar el Job deseado
 * con el que existe. Esto no es pereza: casi todo el JobSpec es inmutable
 * despues de crearse, empezando por el selector y la plantilla del Pod, asi que
 * un dependiente con comparacion normal encuentra diferencias que el servidor de
 * API rechaza, reintenta, vuelve a encontrarlas y se queda girando. Es el bucle
 * clasico al modelar Jobs como dependientes. Un Job es un hecho consumado: o se
 * deja como esta, o se borra y se crea otro.
 *
 * <p>Implementar {@link GarbageCollected} pone una owner reference en el Job, y
 * eso da dos cosas de golpe: el recolector de Kubernetes lo borra cuando
 * desaparece el Backup, y el SDK sabe mapear cada evento de Job a su Backup sin
 * que haya que escribir el mapeo a mano.
 *
 * <p>Sin TTL a proposito. El tamano y la version del servidor viajan en el
 * mensaje de terminacion del Pod, asi que borrar el Job en cuanto termina seria
 * tirar la unica copia de ese dato antes de leerlo.
 */
@KubernetesDependent(useSSA = BooleanWithUndefined.FALSE)
public class BackupJobDependentResource
        extends KubernetesDependentResource<Job, Backup>
        implements Creator<Job, Backup>, GarbageCollected<Backup> {

    public static final String CONTAINER_NAME = "backup";

    @Override
    protected Job desired(Backup backup, Context<Backup> context) {

        ResolvedBackup resolved = context.managedWorkflowAndDependentResourceContext()
                .getMandatory(ResolvedBackup.CONTEXT_KEY, ResolvedBackup.class);

        String name = backup.getMetadata().getName();

        List<EnvVar> env = new ArrayList<>(RunnerJobs.postgresEnv(resolved.source()));
        env.addAll(RunnerJobs.s3Env(resolved.destination(), resolved.objectKey()));
        env.add(RunnerJobs.plainEnv("PGVAULT_FORMAT", resolved.format().name()));
        env.add(RunnerJobs.plainEnv("PGVAULT_COMPRESSION", resolved.compression().name()));

        return new JobBuilder()
                .withNewMetadata()
                .withName(RunnerJobs.backupJobName(name))
                .withNamespace(backup.getMetadata().getNamespace())
                .withLabels(RunnerJobs.labels(name, RunnerJobs.COMPONENT_BACKUP))
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(resolved.backoffLimit())
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(RunnerJobs.labels(name, RunnerJobs.COMPONENT_BACKUP))
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withSecurityContext(RunnerJobs.podSecurityContext())
                .withContainers(RunnerJobs.container(CONTAINER_NAME, null, env))
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }
}
