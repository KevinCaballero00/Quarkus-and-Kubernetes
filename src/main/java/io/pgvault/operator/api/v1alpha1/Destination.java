package io.pgvault.operator.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Required;

/**
 * Donde se guarda el volcado.
 *
 * <p>Hoy solo hay un backend, asi que este envoltorio parece innecesario. No lo
 * es: es el patron con el que Kubernetes modela sus propias uniones, como el
 * origen de un Volume. Anadir GCS o Azure mas adelante seria un campo hermano
 * opcional y una regla CEL que exija exactamente uno, sin romper a nadie. Meter
 * los campos de S3 directamente en el spec cerraria esa puerta para siempre,
 * porque en una API versionada no se pueden mover campos sin migrar a v1beta1.
 */
public class Destination {

    @Required
    @JsonPropertyDescription("Almacenamiento compatible con S3.")
    private S3Destination s3;

    public S3Destination getS3() {
        return s3;
    }

    public void setS3(S3Destination s3) {
        this.s3 = s3;
    }
}
