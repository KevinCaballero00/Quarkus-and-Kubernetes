package io.pgvault.operator.api.v1alpha1;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Una recuperacion a partir de una copia existente.
 *
 * <p>Es la mitad que casi nadie implementa y la unica que demuestra que los
 * backups sirven. Un backup que nunca se ha restaurado no esta probado, esta
 * supuesto.
 */
@Group("pgvault.io")
@Version("v1alpha1")
@Kind("Restore")
@Plural("restores")
@ShortNames("pgr")
@AdditionalPrinterColumn(
        name = "Phase",
        jsonPath = ".status.phase",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Backup",
        jsonPath = ".status.resolvedBackup",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Target-DB",
        jsonPath = ".spec.target.database",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Drop-Existing",
        jsonPath = ".spec.target.dropExisting",
        type = AdditionalPrinterColumn.Type.BOOLEAN)
@AdditionalPrinterColumn(
        name = "Duration",
        jsonPath = ".status.durationSeconds",
        type = AdditionalPrinterColumn.Type.INTEGER,
        priority = 1)
@AdditionalPrinterColumn(
        name = "Age",
        jsonPath = ".metadata.creationTimestamp",
        type = AdditionalPrinterColumn.Type.DATE)
public class Restore extends CustomResource<RestoreSpec, RestoreStatus> implements Namespaced {

    @Override
    protected RestoreStatus initStatus() {
        return new RestoreStatus();
    }
}
