package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.kubernetes.api.model.Condition;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que el operator reporta sobre una politica.
 *
 * <p>Las conditions son el tipo estandar de la maquinaria de Kubernetes, no uno
 * propio. Eso importa mas de lo que parece: cualquier herramienta que ya sepa
 * leer conditions, de kubectl wait a Argo CD, entiende este status sin
 * adaptadores.
 */
public class BackupPolicyStatus {

    @JsonPropertyDescription("Generacion del spec a la que corresponde este status.")
    private Long observedGeneration;

    @JsonPropertyDescription("Condiciones estandar: Ready, Scheduled, StorageReachable.")
    private List<Condition> conditions = new ArrayList<>();

    @JsonPropertyDescription("Ultimo disparo del schedule que el operator ya proceso, lo ejecutara o lo saltara.")
    private String lastScheduleTime;

    @JsonPropertyDescription("Proximo disparo calculado a partir del schedule y la zona horaria.")
    private String nextScheduleTime;

    @JsonPropertyDescription("Resumen de la ultima copia correcta.")
    private BackupSummary lastSuccessfulBackup;

    @JsonPropertyDescription("Backups de esta politica que todavia no han terminado.")
    private List<String> activeBackups = new ArrayList<>();

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public String getLastScheduleTime() {
        return lastScheduleTime;
    }

    public void setLastScheduleTime(String lastScheduleTime) {
        this.lastScheduleTime = lastScheduleTime;
    }

    public String getNextScheduleTime() {
        return nextScheduleTime;
    }

    public void setNextScheduleTime(String nextScheduleTime) {
        this.nextScheduleTime = nextScheduleTime;
    }

    public BackupSummary getLastSuccessfulBackup() {
        return lastSuccessfulBackup;
    }

    public void setLastSuccessfulBackup(BackupSummary lastSuccessfulBackup) {
        this.lastSuccessfulBackup = lastSuccessfulBackup;
    }

    public List<String> getActiveBackups() {
        return activeBackups;
    }

    public void setActiveBackups(List<String> activeBackups) {
        this.activeBackups = activeBackups;
    }
}
