package io.pgvault.operator.api.v1alpha1;

/**
 * Modo TLS de la conexion con PostgreSQL.
 *
 * <p>Las constantes van en PascalCase porque el generador de CRD escribe el
 * nombre de la constante tal cual en el enum del esquema, y las convenciones de
 * la API de Kubernetes piden PascalCase para los valores enumerados. La
 * traduccion a los nombres que entiende libpq vive en {@link #libpqValue()}.
 */
public enum SslMode {

    Disable("disable"),
    Prefer("prefer"),
    Require("require"),
    VerifyCa("verify-ca"),
    VerifyFull("verify-full");

    private final String libpqValue;

    SslMode(String libpqValue) {
        this.libpqValue = libpqValue;
    }

    public String libpqValue() {
        return libpqValue;
    }
}
