package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.ValidationRule;

/**
 * Una ejecucion concreta. La crea el operator desde una politica, o la escribe
 * una persona para lanzar una copia fuera de horario.
 *
 * <p>Ninguno de estos campos lleva valor por defecto, y no es un descuido. El
 * servidor de API aplica los defaults antes de evaluar CEL, asi que un campo con
 * default hace que {@code has(self.campo)} sea siempre cierto y cualquier regla
 * de exclusividad sobre el deje de funcionar. Cuando format o compression se
 * omiten, quien los resuelve es el reconciler: hereda los de la politica, o cae
 * en los del enum.
 */
@ValidationRule(
        value = "self == oldSelf",
        message = "El spec de un Backup es inmutable. Un Backup representa una ejecucion "
                + "que ya ocurrio; para cambiar algo, crea otro.")
@ValidationRule(
        value = "(has(self.policyRef) && !has(self.source) && !has(self.destination)) "
                + "|| (!has(self.policyRef) && has(self.source) && has(self.destination))",
        message = "Indica policyRef, o bien source y destination, pero no ambas cosas.")
@ValidationRule(
        value = "!has(self.policyRef) || (!has(self.format) && !has(self.compression))",
        message = "format y compression solo se pueden fijar en un Backup autonomo. "
                + "Si hay policyRef, los define la politica.")
public class BackupSpec {

    @JsonPropertyDescription("Politica de la que hereda origen, destino, formato y compresion.")
    private LocalObjectRef policyRef;

    @JsonPropertyDescription("Base de datos a volcar, para un Backup autonomo.")
    private PostgresConnection source;

    @JsonPropertyDescription("Destino del volcado, para un Backup autonomo.")
    private Destination destination;

    @JsonPropertyDescription("Formato de salida de pg_dump. Solo valido en un Backup autonomo.")
    private DumpFormat format;

    @JsonPropertyDescription("Compresion del volcado. Solo valida en un Backup autonomo.")
    private CompressionAlgorithm compression;

    public LocalObjectRef getPolicyRef() {
        return policyRef;
    }

    public void setPolicyRef(LocalObjectRef policyRef) {
        this.policyRef = policyRef;
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
}
