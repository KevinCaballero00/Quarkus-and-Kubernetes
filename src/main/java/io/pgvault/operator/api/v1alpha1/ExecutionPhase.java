package io.pgvault.operator.api.v1alpha1;

/**
 * Fase de una ejecucion, compartida por Backup y Restore.
 *
 * <p>La fase es un resumen para el ojo humano y para {@code kubectl get}. La
 * verdad detallada vive en las conditions, que si distinguen el motivo del fallo
 * y a que generacion del spec corresponden. Mantener las dos cosas es la
 * convencion de Kubernetes, no una duplicacion.
 */
public enum ExecutionPhase {

    /** Aceptado, todavia sin Job. */
    Pending,

    /** El Job existe y no ha terminado. */
    Running,

    /** Terminado correctamente. El objeto esta en el bucket. */
    Succeeded,

    /** El Job agoto sus reintentos. */
    Failed
}
