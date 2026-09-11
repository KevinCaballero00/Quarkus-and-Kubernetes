package io.pgvault.operator.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.pgvault.operator.api.v1alpha1.Backup;
import io.pgvault.operator.api.v1alpha1.BackupPolicy;
import io.pgvault.operator.api.v1alpha1.BackupSpec;
import io.pgvault.operator.api.v1alpha1.CompressionAlgorithm;
import io.pgvault.operator.api.v1alpha1.Destination;
import io.pgvault.operator.api.v1alpha1.DumpFormat;
import io.pgvault.operator.api.v1alpha1.PostgresConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Convierte el spec de un Backup en los valores concretos con los que se
 * construye el Job.
 *
 * <p>La politica se lee con una consulta directa al servidor de API, no desde un
 * informer. Es deliberado: solo se consulta mientras el Backup no tiene Job, o
 * sea una vez en toda su vida, y montar un segundo cache para eso costaria
 * memoria proporcional al numero de politicas del cluster a cambio de nada.
 */
@ApplicationScoped
public class BackupResolver {

    /**
     * Marca de tiempo de la clave del objeto. Segundos y sufijo Z, sin
     * separadores dentro de la hora: tiene que ser ordenable como texto y valida
     * como parte de una clave de S3.
     */
    private static final DateTimeFormatter OBJECT_KEY_STAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final int DEFAULT_BACKOFF_LIMIT = 2;

    public ResolvedBackup resolve(Backup backup, KubernetesClient client) {

        BackupSpec spec = backup.getSpec();
        if (spec == null) {
            throw new BackupResolutionException("El Backup no tiene spec.");
        }

        PostgresConnection source;
        Destination destination;
        DumpFormat format;
        CompressionAlgorithm compression;
        int backoffLimit;

        if (spec.getPolicyRef() != null) {
            String policyName = spec.getPolicyRef().getName();
            String namespace = backup.getMetadata().getNamespace();

            BackupPolicy policy = client.resources(BackupPolicy.class)
                    .inNamespace(namespace)
                    .withName(policyName)
                    .get();

            if (policy == null) {
                throw new BackupResolutionException(
                        "La politica " + policyName + " no existe en el namespace " + namespace + ".");
            }
            if (policy.getSpec() == null) {
                throw new BackupResolutionException("La politica " + policyName + " no tiene spec.");
            }

            source = policy.getSpec().getSource();
            destination = policy.getSpec().getDestination();
            format = policy.getSpec().getFormat();
            compression = policy.getSpec().getCompression();
            backoffLimit = policy.getSpec().getBackoffLimit() != null
                    ? policy.getSpec().getBackoffLimit()
                    : DEFAULT_BACKOFF_LIMIT;
        } else {
            source = spec.getSource();
            destination = spec.getDestination();
            format = spec.getFormat();
            compression = spec.getCompression();
            backoffLimit = DEFAULT_BACKOFF_LIMIT;
        }

        // Los enum sin valor llegan aqui cuando el Backup es autonomo y los
        // omitio: el esquema no les pone default a proposito, porque un default
        // rompe las reglas CEL de exclusividad. Resolverlos es tarea de este
        // codigo, y estos son los mismos valores que la politica declara.
        if (format == null) {
            format = DumpFormat.Custom;
        }
        if (compression == null) {
            compression = CompressionAlgorithm.Zstd;
        }

        if (source == null || destination == null || destination.getS3() == null) {
            throw new BackupResolutionException(
                    "No hay origen o destino que usar. Revisa el spec del Backup o el de su politica.");
        }

        return new ResolvedBackup(source, destination, format, compression, backoffLimit,
                objectKey(backup, destination, format, compression));
    }

    /**
     * Clave definitiva del objeto dentro del bucket.
     *
     * <p>Se calcula a partir de datos que ya no cambian nunca, el nombre del
     * recurso y su instante de creacion, para que recalcularla despues de un
     * reinicio del operator devuelva exactamente la misma cadena. Si dependiera
     * del reloj de la reconciliacion, un operator que se reiniciara entre lanzar
     * el Job y escribir el status perderia el rastro del objeto que acaba de
     * crear.
     */
    static String objectKey(Backup backup,
                            Destination destination,
                            DumpFormat format,
                            CompressionAlgorithm compression) {

        String prefix = destination.getS3().getPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "";
        } else if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }

        String created = backup.getMetadata().getCreationTimestamp();
        String stamp = created != null
                ? OBJECT_KEY_STAMP.format(Instant.parse(created))
                : OBJECT_KEY_STAMP.format(Instant.now());

        return prefix
                + backup.getMetadata().getName()
                + "-" + stamp
                + format.fileSuffix()
                + compression.fileSuffix();
    }
}
