package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.kubernetes.api.model.Condition;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que el operator reporta sobre una recuperacion.
 *
 * <p>resolvedBackup deja por escrito que copia se eligio. Con fromPolicy la
 * eleccion la hace el operator, y sin dejar rastro nadie podria auditar despues
 * desde que punto se recupero.
 */
public class RestoreStatus {

    @JsonPropertyDescription("Generacion del spec a la que corresponde este status.")
    private Long observedGeneration;

    @JsonPropertyDescription("Resumen legible del estado de la recuperacion.")
    private ExecutionPhase phase;

    @JsonPropertyDescription("Condiciones estandar de Kubernetes con el detalle real.")
    private List<Condition> conditions = new ArrayList<>();

    @JsonPropertyDescription("Backup que el operator eligio finalmente restaurar.")
    private String resolvedBackup;

    @JsonPropertyDescription("Job que ejecuta la restauracion.")
    private String jobName;

    @JsonPropertyDescription("Instante en que arranco.")
    private String startTime;

    @JsonPropertyDescription("Instante en que termino, con exito o sin el.")
    private String completionTime;

    @JsonPropertyDescription("Duracion en segundos.")
    private Long durationSeconds;

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

    public String getResolvedBackup() {
        return resolvedBackup;
    }

    public void setResolvedBackup(String resolvedBackup) {
        this.resolvedBackup = resolvedBackup;
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
}
