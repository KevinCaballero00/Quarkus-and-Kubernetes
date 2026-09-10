package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Required;

/**
 * Referencia a otro objeto del mismo namespace.
 *
 * <p>A proposito no lleva campo de namespace. Permitir referencias cruzadas
 * obligaria al operator a tener permisos de lectura sobre todo el cluster y
 * abriria la puerta a que una politica de un equipo apunte al Secret de otro.
 */
public class LocalObjectRef {

    @Required
    @JsonPropertyDescription("Nombre del objeto referenciado, que debe vivir en el mismo namespace.")
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
