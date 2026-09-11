package io.pgvault.operator.controller;

import io.pgvault.operator.api.v1alpha1.CompressionAlgorithm;
import io.pgvault.operator.api.v1alpha1.Destination;
import io.pgvault.operator.api.v1alpha1.DumpFormat;
import io.pgvault.operator.api.v1alpha1.PostgresConnection;

/**
 * Lo que hace falta para construir el Job, ya sin herencia pendiente.
 *
 * <p>El spec de un Backup puede ser una sola linea, {@code policyRef}, o traer
 * origen y destino completos. El reconciler resuelve esa diferencia una sola
 * vez, al principio, y a partir de ahi nadie mas vuelve a preguntar de donde
 * salio cada valor.
 *
 * @param objectKey clave del objeto, calculada por el operator antes de lanzar
 *                  el Job, nunca por el runner
 */
public record ResolvedBackup(PostgresConnection source,
                             Destination destination,
                             DumpFormat format,
                             CompressionAlgorithm compression,
                             int backoffLimit,
                             String objectKey) {

    /** Clave con la que viaja por el contexto del workflow hasta el dependent resource. */
    public static final String CONTEXT_KEY = "pgvault.resolved-backup";
}
