package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Required;

/**
 * Como llegar a la base de datos que se va a volcar o restaurar.
 *
 * <p>pgvault no gestiona PostgreSQL: apunta a una base que ya existe, venga de
 * CloudNativePG, de un StatefulSet o de un servicio administrado fuera del
 * cluster. Por eso el contrato es una direccion y un Secret, no una instancia.
 */
public class PostgresConnection {

    @Required
    @JsonPropertyDescription("Host o nombre de servicio de PostgreSQL.")
    private String host;

    @Min(1)
    @Max(65535)
    @Default("5432")
    @JsonPropertyDescription("Puerto de PostgreSQL.")
    private Integer port = 5432;

    @Required
    @JsonPropertyDescription("Nombre de la base de datos.")
    private String database;

    @Required
    @JsonPropertyDescription("Secret del mismo namespace con el usuario y la contrasena.")
    private CredentialsRef credentialsSecretRef;

    @Default("Prefer")
    @JsonPropertyDescription("Modo TLS de la conexion.")
    private SslMode sslMode = SslMode.Prefer;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public CredentialsRef getCredentialsSecretRef() {
        return credentialsSecretRef;
    }

    public void setCredentialsSecretRef(CredentialsRef credentialsSecretRef) {
        this.credentialsSecretRef = credentialsSecretRef;
    }

    public SslMode getSslMode() {
        return sslMode;
    }

    public void setSslMode(SslMode sslMode) {
        this.sslMode = sslMode;
    }
}
