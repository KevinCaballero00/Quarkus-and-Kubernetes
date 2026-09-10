package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;

/**
 * Almacenamiento compatible con S3 donde aterriza el volcado.
 *
 * <p>forcePathStyle viene activado porque MinIO, Ceph y casi todo lo que no es
 * AWS sirve los buckets por ruta y no por subdominio. Contra AWS real hay que
 * desactivarlo.
 */
public class S3Destination {

    @Required
    @Pattern("^https?://.+")
    @JsonPropertyDescription("Endpoint del servicio S3, con esquema http o https.")
    private String endpoint;

    @Required
    @Pattern("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
    @JsonPropertyDescription("Nombre del bucket. Debe cumplir las reglas de nombrado de S3.")
    private String bucket;

    // Sin @Default a proposito: el generador traduce la cadena vacia a
    // "default: null", que no es un valor valido para un campo de tipo string.
    // Un prefijo ausente ya significa exactamente lo mismo.
    @JsonPropertyDescription("Prefijo bajo el que se agrupan los objetos de esta politica.")
    private String prefix;

    @Default("us-east-1")
    @JsonPropertyDescription("Region. MinIO la ignora, pero el protocolo la exige al firmar.")
    private String region = "us-east-1";

    @Default("true")
    @JsonPropertyDescription("Direcciona el bucket por ruta en vez de por subdominio. Necesario en MinIO.")
    private Boolean forcePathStyle = true;

    @Required
    @JsonPropertyDescription("Secret del mismo namespace con las credenciales de S3.")
    private S3CredentialsRef credentialsSecretRef;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Boolean getForcePathStyle() {
        return forcePathStyle;
    }

    public void setForcePathStyle(Boolean forcePathStyle) {
        this.forcePathStyle = forcePathStyle;
    }

    public S3CredentialsRef getCredentialsSecretRef() {
        return credentialsSecretRef;
    }

    public void setCredentialsSecretRef(S3CredentialsRef credentialsSecretRef) {
        this.credentialsSecretRef = credentialsSecretRef;
    }
}
