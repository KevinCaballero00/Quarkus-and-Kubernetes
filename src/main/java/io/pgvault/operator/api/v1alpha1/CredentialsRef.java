package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Required;

/**
 * Secret del mismo namespace con el usuario y la contrasena de PostgreSQL.
 *
 * <p>Las claves son configurables porque los Secret rara vez se crean para este
 * operator: suelen venir de CloudNativePG, de un chart de Helm ajeno o de un
 * gestor externo, y cada uno nombra sus claves a su manera. Obligar a un nombre
 * concreto forzaria a copiar Secrets, que es justo lo que no se debe hacer.
 */
public class CredentialsRef {

    @Required
    @JsonPropertyDescription("Nombre del Secret que guarda las credenciales.")
    private String name;

    @Default("username")
    @JsonPropertyDescription("Clave del Secret que contiene el usuario.")
    private String usernameKey = "username";

    @Default("password")
    @JsonPropertyDescription("Clave del Secret que contiene la contrasena.")
    private String passwordKey = "password";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUsernameKey() {
        return usernameKey;
    }

    public void setUsernameKey(String usernameKey) {
        this.usernameKey = usernameKey;
    }

    public String getPasswordKey() {
        return passwordKey;
    }

    public void setPasswordKey(String passwordKey) {
        this.passwordKey = passwordKey;
    }
}
