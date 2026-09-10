package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;

/**
 * Base de datos donde se vuelca la copia.
 *
 * <p>Hereda de {@link PostgresConnection} para que el YAML quede plano y sin un
 * nivel de anidamiento inutil, y para no repetir el contrato de conexion.
 *
 * <p>dropExisting es false por defecto y es la unica opcion destructiva de toda
 * la API. Restaurar sobre una base con datos deberia costar escribir una linea
 * de mas, porque la alternativa es que alguien lo descubra despues.
 */
public class RestoreTarget extends PostgresConnection {

    @Default("false")
    @JsonPropertyDescription("Borra los objetos existentes antes de restaurar. Destructivo.")
    private Boolean dropExisting = false;

    public Boolean getDropExisting() {
        return dropExisting;
    }

    public void setDropExisting(Boolean dropExisting) {
        this.dropExisting = dropExisting;
    }
}
