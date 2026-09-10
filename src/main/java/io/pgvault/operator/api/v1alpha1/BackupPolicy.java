package io.pgvault.operator.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Politica declarativa de backup para una base de datos PostgreSQL.
 *
 * <p>En el modulo 1 el spec solo lleva el schedule: lo que se valida aqui no es
 * la logica de negocio sino el ciclo completo, que el CRD se genere, se instale
 * y que el reconciler consiga escribir en el subrecurso de status.
 *
 * <p>Namespaced a proposito. Una politica se refiere a una base concreta y a un
 * Secret con sus credenciales, y ambos viven en un namespace. Un CRD de alcance
 * de cluster obligaria a resolver referencias entre namespaces, que es
 * exactamente el tipo de permiso amplio que un operator deberia evitar.
 */
@Group("pgvault.io")
@Version("v1alpha1")
@Kind("BackupPolicy")
@Plural("backuppolicies")
@ShortNames("bpol")
public class BackupPolicy extends CustomResource<BackupPolicySpec, BackupPolicyStatus>
        implements Namespaced {

    @Override
    protected BackupPolicyStatus initStatus() {
        return new BackupPolicyStatus();
    }
}
