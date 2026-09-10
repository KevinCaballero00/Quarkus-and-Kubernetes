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
 * Una ejecucion de backup y su resultado.
 *
 * <p>Estos recursos son el inventario. La retencion del modulo 6 se limita a
 * borrar los que sobran, y el finalizer del modulo 4 se encarga de que borrar el
 * recurso borre tambien su objeto en el bucket. De ahi que el estado del
 * almacenamiento sea siempre una consecuencia del estado de la API, y no algo
 * que haya que reconciliar aparte.
 */
@Group("pgvault.io")
@Version("v1alpha1")
@Kind("Backup")
@Plural("backups")
@ShortNames("pgb")
@AdditionalPrinterColumn(
        name = "Phase",
        jsonPath = ".status.phase",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Policy",
        jsonPath = ".spec.policyRef.name",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Size",
        jsonPath = ".status.sizeBytes",
        type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(
        name = "Duration",
        jsonPath = ".status.durationSeconds",
        type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(
        name = "Object",
        jsonPath = ".status.objectKey",
        type = AdditionalPrinterColumn.Type.STRING,
        priority = 1)
@AdditionalPrinterColumn(
        name = "Age",
        jsonPath = ".metadata.creationTimestamp",
        type = AdditionalPrinterColumn.Type.DATE)
public class Backup extends CustomResource<BackupSpec, BackupStatus> implements Namespaced {

    @Override
    protected BackupStatus initStatus() {
        return new BackupStatus();
    }
}
