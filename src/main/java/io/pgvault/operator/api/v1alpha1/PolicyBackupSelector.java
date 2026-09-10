package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;

/**
 * Elige un backup de una politica sin nombrarlo.
 *
 * <p>Es la forma que se usa de verdad en una recuperacion: nadie recuerda el
 * nombre generado de la copia de anoche, pero todo el mundo sabe decir "la
 * ultima buena antes de que desplegaramos".
 */
@ValidationRule(
        value = "self.mode != 'Before' || has(self.before)",
        message = "Con mode: Before hay que indicar el instante en el campo before.")
public class PolicyBackupSelector {

    @Required
    @JsonPropertyDescription("Politica de la que se toman las copias candidatas.")
    private LocalObjectRef policyRef;

    @Default("Latest")
    @JsonPropertyDescription("Criterio de seleccion.")
    private BackupSelectorMode mode = BackupSelectorMode.Latest;

    @Pattern("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$")
    @JsonPropertyDescription("Instante en formato RFC 3339. Solo se usa con mode: Before.")
    private String before;

    public LocalObjectRef getPolicyRef() {
        return policyRef;
    }

    public void setPolicyRef(LocalObjectRef policyRef) {
        this.policyRef = policyRef;
    }

    public BackupSelectorMode getMode() {
        return mode;
    }

    public void setMode(BackupSelectorMode mode) {
        this.mode = mode;
    }

    public String getBefore() {
        return before;
    }

    public void setBefore(String before) {
        this.before = before;
    }
}
