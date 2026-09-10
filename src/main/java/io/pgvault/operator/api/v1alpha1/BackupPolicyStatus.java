package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Lo que el operator reporta. En el modulo 2 crece con conditions al estilo
 * estandar de Kubernetes, ultimo y proximo disparo, y el ultimo backup correcto.
 *
 * <p>observedGeneration es el campo que permite distinguir "todavia no he visto
 * tu cambio" de "lo vi y este es el resultado". Sin el, un usuario no puede
 * saber si el status que esta leyendo corresponde al spec que acaba de aplicar.
 */
public class BackupPolicyStatus {

    @JsonPropertyDescription("Instante en el que el operator observo esta politica por ultima vez.")
    private String observedAt;

    @JsonPropertyDescription("Generacion del spec a la que corresponde esta observacion.")
    private Long observedGeneration;

    public String getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(String observedAt) {
        this.observedAt = observedAt;
    }

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }
}
