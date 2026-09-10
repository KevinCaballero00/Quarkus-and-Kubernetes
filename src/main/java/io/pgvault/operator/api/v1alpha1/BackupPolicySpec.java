package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;

/**
 * Lo que el usuario declara: de donde, cuando, a donde y cuanto se conserva.
 */
public class BackupPolicySpec {

    /**
     * Cinco campos separados por espacios, o una de las macros habituales.
     *
     * <p>El regex solo filtra lo barato: numero de campos y caracteres validos.
     * No puede saber que "0 3 31 2 *" no ocurre nunca, ni que "*&#47;0" es una
     * division por cero. Esa validacion real la hace el parser de cron en el
     * modulo 5, y su fallo se reporta en las conditions. Poner aqui un regex mas
     * ambicioso daria una falsa sensacion de cobertura.
     */
    private static final String CRON_PATTERN =
            "^(@(hourly|daily|weekly|monthly|yearly|annually)"
                    + "|(\\*|[0-9]+)([-,/][0-9]+)*(\\s+(\\*|[0-9]+)([-,/][0-9]+)*){4})$";

    @Default("false")
    @JsonPropertyDescription("Detiene los disparos sin borrar la politica ni las copias existentes.")
    private Boolean suspend = false;

    @Required
    @Pattern(CRON_PATTERN)
    @JsonPropertyDescription("Cuando se dispara el backup, en formato cron de cinco campos.")
    private String schedule;

    @Default("UTC")
    @JsonPropertyDescription("Zona horaria en la que se interpreta el schedule, por ejemplo America/Bogota.")
    private String timeZone = "UTC";

    @Required
    @JsonPropertyDescription("Base de datos que se va a volcar.")
    private PostgresConnection source;

    @Required
    @JsonPropertyDescription("Donde aterrizan los volcados.")
    private Destination destination;

    @Default("Custom")
    @JsonPropertyDescription("Formato de salida de pg_dump.")
    private DumpFormat format = DumpFormat.Custom;

    @Default("Zstd")
    @JsonPropertyDescription("Compresion aplicada al volcado.")
    private CompressionAlgorithm compression = CompressionAlgorithm.Zstd;

    @Default("Forbid")
    @JsonPropertyDescription("Que hacer si toca disparar y el backup anterior sigue corriendo.")
    private ConcurrencyPolicy concurrencyPolicy = ConcurrencyPolicy.Forbid;

    @JsonPropertyDescription("Cuantas copias se conservan. Si se omite, no se borra nada nunca.")
    private RetentionPolicy retention;

    @Min(0)
    @Default("2")
    @JsonPropertyDescription("Reintentos del Job antes de dar el backup por fallido.")
    private Integer backoffLimit = 2;

    public Boolean getSuspend() {
        return suspend;
    }

    public void setSuspend(Boolean suspend) {
        this.suspend = suspend;
    }

    public String getSchedule() {
        return schedule;
    }

    public void setSchedule(String schedule) {
        this.schedule = schedule;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public PostgresConnection getSource() {
        return source;
    }

    public void setSource(PostgresConnection source) {
        this.source = source;
    }

    public Destination getDestination() {
        return destination;
    }

    public void setDestination(Destination destination) {
        this.destination = destination;
    }

    public DumpFormat getFormat() {
        return format;
    }

    public void setFormat(DumpFormat format) {
        this.format = format;
    }

    public CompressionAlgorithm getCompression() {
        return compression;
    }

    public void setCompression(CompressionAlgorithm compression) {
        this.compression = compression;
    }

    public ConcurrencyPolicy getConcurrencyPolicy() {
        return concurrencyPolicy;
    }

    public void setConcurrencyPolicy(ConcurrencyPolicy concurrencyPolicy) {
        this.concurrencyPolicy = concurrencyPolicy;
    }

    public RetentionPolicy getRetention() {
        return retention;
    }

    public void setRetention(RetentionPolicy retention) {
        this.retention = retention;
    }

    public Integer getBackoffLimit() {
        return backoffLimit;
    }

    public void setBackoffLimit(Integer backoffLimit) {
        this.backoffLimit = backoffLimit;
    }
}
