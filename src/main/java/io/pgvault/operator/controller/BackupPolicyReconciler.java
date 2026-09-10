package io.pgvault.operator.controller;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.pgvault.operator.api.v1alpha1.BackupPolicy;
import io.pgvault.operator.api.v1alpha1.BackupPolicyStatus;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Reconciler minimo del modulo 1: sella una marca de tiempo en el status.
 *
 * <p>No hace nada util todavia, y ese es el objetivo. Si esto funciona, quedan
 * verificados el CRD generado, el registro del controlador, los permisos RBAC
 * de lectura y de escritura sobre el subrecurso de status, y la conexion con el
 * cluster. Cuando el modulo 5 traiga el planificador, cualquier fallo sera de la
 * logica nueva y no de la fontaneria.
 */
@ApplicationScoped
public class BackupPolicyReconciler implements Reconciler<BackupPolicy> {

    private static final Logger LOG = Logger.getLogger(BackupPolicyReconciler.class);

    @Override
    public UpdateControl<BackupPolicy> reconcile(BackupPolicy policy, Context<BackupPolicy> context) {
        String schedule = policy.getSpec() == null ? "<sin spec>" : policy.getSpec().getSchedule();

        LOG.infof("Reconciliando %s/%s con schedule %s",
                policy.getMetadata().getNamespace(),
                policy.getMetadata().getName(),
                schedule);

        BackupPolicyStatus status = new BackupPolicyStatus();
        status.setObservedAt(Instant.now().toString());
        status.setObservedGeneration(policy.getMetadata().getGeneration());
        policy.setStatus(status);

        return UpdateControl.patchStatus(policy);
    }
}
