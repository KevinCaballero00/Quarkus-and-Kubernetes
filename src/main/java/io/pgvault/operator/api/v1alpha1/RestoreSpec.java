package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;

/**
 * Una recuperacion: de que copia, hacia que base.
 *
 * <p>Inmutable como el Backup, y por la misma razon. Cambiar el destino de una
 * restauracion a medio camino no tiene un significado util, y si tiene una
 * lectura peligrosa.
 */
@ValidationRule(
        value = "self == oldSelf",
        message = "El spec de un Restore es inmutable. Para recuperar otra cosa, crea otro.")
@ValidationRule(
        value = "has(self.backupRef) != has(self.fromPolicy)",
        message = "Indica backupRef para nombrar una copia concreta, o fromPolicy para "
                + "elegirla por criterio, pero no ambas.")
public class RestoreSpec {

    @JsonPropertyDescription("Copia concreta que se va a restaurar.")
    private LocalObjectRef backupRef;

    @JsonPropertyDescription("Elige la copia por criterio dentro de una politica.")
    private PolicyBackupSelector fromPolicy;

    @Required
    @JsonPropertyDescription("Base de datos donde se restaura.")
    private RestoreTarget target;

    public LocalObjectRef getBackupRef() {
        return backupRef;
    }

    public void setBackupRef(LocalObjectRef backupRef) {
        this.backupRef = backupRef;
    }

    public PolicyBackupSelector getFromPolicy() {
        return fromPolicy;
    }

    public void setFromPolicy(PolicyBackupSelector fromPolicy) {
        this.fromPolicy = fromPolicy;
    }

    public RestoreTarget getTarget() {
        return target;
    }

    public void setTarget(RestoreTarget target) {
        this.target = target;
    }
}
