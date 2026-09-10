package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.ValidationRule;

/**
 * Cuantas copias se conservan y cuales se descartan.
 *
 * <p>Los criterios se suman, no se pisan: un backup sobrevive si lo salva
 * cualquiera de ellos. Es el mismo modelo que usan restic y borg, y evita la
 * sorpresa de perder la copia semanal por haber puesto un keepLast bajo.
 *
 * <p>Una retencion sin ningun criterio no significa "conservar todo": significa
 * que quien la escribio se dejo algo. La regla CEL lo rechaza en el servidor de
 * API en vez de dejar que el operator adivine.
 */
@ValidationRule(
        value = "has(self.keepLast) || has(self.keepDaily) || has(self.keepWeekly) "
                + "|| has(self.keepMonthly) || has(self.maxAge)",
        message = "La retencion necesita al menos un criterio: keepLast, keepDaily, "
                + "keepWeekly, keepMonthly o maxAge.")
public class RetentionPolicy {

    @Min(1)
    @JsonPropertyDescription("Conserva las N copias correctas mas recientes.")
    private Integer keepLast;

    @Min(1)
    @JsonPropertyDescription("Conserva la copia mas reciente de cada uno de los ultimos N dias.")
    private Integer keepDaily;

    @Min(1)
    @JsonPropertyDescription("Conserva la copia mas reciente de cada una de las ultimas N semanas.")
    private Integer keepWeekly;

    @Min(1)
    @JsonPropertyDescription("Conserva la copia mas reciente de cada uno de los ultimos N meses.")
    private Integer keepMonthly;

    @Pattern("^[0-9]+[hdw]$")
    @JsonPropertyDescription("Descarta lo mas viejo que esta edad, por ejemplo 30d, 12h o 4w.")
    private String maxAge;

    public Integer getKeepLast() {
        return keepLast;
    }

    public void setKeepLast(Integer keepLast) {
        this.keepLast = keepLast;
    }

    public Integer getKeepDaily() {
        return keepDaily;
    }

    public void setKeepDaily(Integer keepDaily) {
        this.keepDaily = keepDaily;
    }

    public Integer getKeepWeekly() {
        return keepWeekly;
    }

    public void setKeepWeekly(Integer keepWeekly) {
        this.keepWeekly = keepWeekly;
    }

    public Integer getKeepMonthly() {
        return keepMonthly;
    }

    public void setKeepMonthly(Integer keepMonthly) {
        this.keepMonthly = keepMonthly;
    }

    public String getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(String maxAge) {
        this.maxAge = maxAge;
    }
}
