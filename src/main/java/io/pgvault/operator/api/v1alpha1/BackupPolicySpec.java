package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Required;

/**
 * Lo que el usuario declara. En el modulo 2 crece con origen, destino S3,
 * retencion, zona horaria y politica de concurrencia.
 */
public class BackupPolicySpec {

    @Required
    @JsonPropertyDescription("Expresion cron que define cuando se dispara el backup, por ejemplo '0 3 * * *'.")
    private String schedule;

    public String getSchedule() {
        return schedule;
    }

    public void setSchedule(String schedule) {
        this.schedule = schedule;
    }
}
