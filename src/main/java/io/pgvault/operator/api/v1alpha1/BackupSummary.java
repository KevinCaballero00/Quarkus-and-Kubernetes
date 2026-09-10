package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Resumen del ultimo backup correcto, copiado en el status de la politica.
 *
 * <p>Es una desnormalizacion deliberada. Sin ella, responder "cuando fue la
 * ultima copia buena" obliga a listar todos los Backup del namespace y
 * ordenarlos, y esa es justo la pregunta que se hace en una guardia a las tres
 * de la manana, cuando lo que hay a mano es un kubectl get.
 */
public class BackupSummary {

    @JsonPropertyDescription("Nombre del recurso Backup.")
    private String name;

    @JsonPropertyDescription("Instante en que termino.")
    private String completionTime;

    @JsonPropertyDescription("Tamano del objeto subido, en bytes.")
    private Long sizeBytes;

    @JsonPropertyDescription("Clave del objeto dentro del bucket.")
    private String objectKey;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCompletionTime() {
        return completionTime;
    }

    public void setCompletionTime(String completionTime) {
        this.completionTime = completionTime;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }
}
