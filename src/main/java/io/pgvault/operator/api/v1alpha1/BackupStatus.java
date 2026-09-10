package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.kubernetes.api.model.Condition;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que el operator reporta sobre una ejecucion.
 *
 * <p>objectKey y checksumSHA256 son lo que convierte este recurso en un
 * inventario utilizable: con ellos se puede verificar o recuperar una copia a
 * mano, sin el operator delante. Un backup del que no se sabe donde esta ni si
 * llego entero no es un backup.
 */
public class BackupStatus {

    @JsonPropertyDescription("Generacion del spec a la que corresponde este status.")
    private Long observedGeneration;

    @JsonPropertyDescription("Resumen legible del estado de la ejecucion.")
    private ExecutionPhase phase;

    @JsonPropertyDescription("Condiciones estandar de Kubernetes con el detalle real.")
    private List<Condition> conditions = new ArrayList<>();

    @JsonPropertyDescription("Job que ejecuta el volcado.")
    private String jobName;

    @JsonPropertyDescription("Instante en que arranco.")
    private String startTime;

    @JsonPropertyDescription("Instante en que termino, con exito o sin el.")
    private String completionTime;

    @JsonPropertyDescription("Duracion en segundos.")
    private Long durationSeconds;

    @JsonPropertyDescription("Clave del objeto dentro del bucket.")
    private String objectKey;

    @JsonPropertyDescription("Tamano del objeto subido, en bytes.")
    private Long sizeBytes;

    @JsonPropertyDescription("Suma SHA-256 del objeto, para verificar la copia sin descargarla entera.")
    private String checksumSHA256;

    @JsonPropertyDescription("Version del servidor PostgreSQL de origen. pg_restore la necesita.")
    private String postgresVersion;

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public ExecutionPhase getPhase() {
        return phase;
    }

    public void setPhase(ExecutionPhase phase) {
        this.phase = phase;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getCompletionTime() {
        return completionTime;
    }

    public void setCompletionTime(String completionTime) {
        this.completionTime = completionTime;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getChecksumSHA256() {
        return checksumSHA256;
    }

    public void setChecksumSHA256(String checksumSHA256) {
        this.checksumSHA256 = checksumSHA256;
    }

    public String getPostgresVersion() {
        return postgresVersion;
    }

    public void setPostgresVersion(String postgresVersion) {
        this.postgresVersion = postgresVersion;
    }
}
