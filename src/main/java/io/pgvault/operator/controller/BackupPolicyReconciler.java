package io.pgvault.operator.controller;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.pgvault.operator.api.v1alpha1.BackupPolicy;
import io.pgvault.operator.api.v1alpha1.BackupPolicyStatus;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;

/**
 * Reconciler de politicas.
 *
 * <p>En el modulo 2 solo acusa recibo: marca la generacion observada y publica
 * una condition Ready. El planificador de verdad, con calculo del proximo
 * disparo y creacion de Backups, llega en el modulo 5.
 */
@ApplicationScoped
public class BackupPolicyReconciler implements Reconciler<BackupPolicy> {

    private static final Logger LOG = Logger.getLogger(BackupPolicyReconciler.class);

    @Override
    public UpdateControl<BackupPolicy> reconcile(BackupPolicy policy, Context<BackupPolicy> context) {

        Long generation = policy.getMetadata().getGeneration();

        LOG.debugf("Reconciliando %s/%s, generacion %d",
                policy.getMetadata().getNamespace(),
                policy.getMetadata().getName(),
                generation);

        BackupPolicyStatus status = policy.getStatus();
        if (status == null) {
            status = new BackupPolicyStatus();
        }
        if (status.getConditions() == null) {
            status.setConditions(new ArrayList<>());
        }

        status.setObservedGeneration(generation);

        Conditions.set(status.getConditions(),
                Conditions.READY,
                true,
                "Accepted",
                "La politica es sintacticamente valida. El planificador llega en el modulo 5.",
                generation);

        policy.setStatus(status);

        return UpdateControl.patchStatus(policy);
    }
}
