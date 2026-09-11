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
 * Politica declarativa de backup para una base de datos PostgreSQL.
 *
 * <p>Namespaced a proposito. Una politica se refiere a una base concreta y a los
 * Secret con sus credenciales, y ambos viven en un namespace. Un CRD de alcance
 * de cluster obligaria a resolver referencias entre namespaces, que es
 * exactamente el tipo de permiso amplio que un operator deberia evitar.
 *
 * <p>Las columnas de impresion responden sin abrir el YAML las tres preguntas
 * que se hacen de verdad: cuando toca la proxima, cuando fue la ultima buena, y
 * si el operator la considera sana.
 */
@Group("pgvault.io")
@Version("v1alpha1")
@Kind("BackupPolicy")
@Plural("backuppolicies")
@ShortNames("bpol")
@AdditionalPrinterColumn(
        name = "Schedule",
        jsonPath = ".spec.schedule",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Suspended",
        jsonPath = ".spec.suspend",
        type = AdditionalPrinterColumn.Type.BOOLEAN)
@AdditionalPrinterColumn(
        name = "Last-Backup",
        jsonPath = ".status.lastSuccessfulBackup.completionTime",
        type = AdditionalPrinterColumn.Type.DATE)
// STRING y no DATE, aunque sea una fecha. kubectl no imprime las columnas de
// tipo DATE: imprime la antiguedad, y la antiguedad de algo que aun no ha
// pasado es negativa, asi que sale como "<invalid>". DATE sirve para lo que ya
// ocurrio, que es el caso de Last-Backup y de Age.
@AdditionalPrinterColumn(
        name = "Next-Run",
        jsonPath = ".status.nextScheduleTime",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Ready",
        jsonPath = ".status.conditions[?(@.type=='Ready')].status",
        type = AdditionalPrinterColumn.Type.STRING)
@AdditionalPrinterColumn(
        name = "Timezone",
        jsonPath = ".spec.timeZone",
        type = AdditionalPrinterColumn.Type.STRING,
        priority = 1)
@AdditionalPrinterColumn(
        name = "Age",
        jsonPath = ".metadata.creationTimestamp",
        type = AdditionalPrinterColumn.Type.DATE)
public class BackupPolicy extends CustomResource<BackupPolicySpec, BackupPolicyStatus>
        implements Namespaced {

    @Override
    protected BackupPolicyStatus initStatus() {
        return new BackupPolicyStatus();
    }
}
