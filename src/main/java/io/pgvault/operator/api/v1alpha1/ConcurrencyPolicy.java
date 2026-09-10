package io.pgvault.operator.api.v1alpha1;

/**
 * Que hacer cuando toca disparar un backup y el anterior sigue corriendo.
 *
 * <p>Forbid es el predeterminado. Un volcado que tarda mas que su intervalo es
 * una senal de que algo va mal, y encadenar copias solapadas sobre la misma base
 * multiplica la carga justo cuando menos conviene.
 */
public enum ConcurrencyPolicy {

    /** Deja correr los backups en paralelo. */
    Allow,

    /** Se salta el disparo si hay uno en curso. */
    Forbid,

    /** Cancela el que este en curso y arranca el nuevo. */
    Replace
}
