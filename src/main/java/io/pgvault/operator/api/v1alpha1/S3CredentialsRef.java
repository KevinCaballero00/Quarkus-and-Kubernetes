package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Required;

/**
 * Secret del mismo namespace con las credenciales del almacenamiento S3.
 *
 * <p>Tipo separado de {@link CredentialsRef} a proposito: las claves por defecto
 * son las variables de entorno que ya entienden el AWS CLI y casi cualquier
 * cliente S3, asi que un Secret creado para otra herramienta suele valer tal cual.
 */
public class S3CredentialsRef {

    @Required
    @JsonPropertyDescription("Nombre del Secret que guarda las credenciales de S3.")
    private String name;

    @Default("AWS_ACCESS_KEY_ID")
    @JsonPropertyDescription("Clave del Secret que contiene el identificador de acceso.")
    private String accessKeyIdKey = "AWS_ACCESS_KEY_ID";

    @Default("AWS_SECRET_ACCESS_KEY")
    @JsonPropertyDescription("Clave del Secret que contiene la clave secreta.")
    private String secretAccessKeyKey = "AWS_SECRET_ACCESS_KEY";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccessKeyIdKey() {
        return accessKeyIdKey;
    }

    public void setAccessKeyIdKey(String accessKeyIdKey) {
        this.accessKeyIdKey = accessKeyIdKey;
    }

    public String getSecretAccessKeyKey() {
        return secretAccessKeyKey;
    }

    public void setSecretAccessKeyKey(String secretAccessKeyKey) {
        this.secretAccessKeyKey = secretAccessKeyKey;
    }
}
