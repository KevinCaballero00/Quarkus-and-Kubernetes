package io.pgvault.operator.api.v1alpha1;

/**
 * Como elegir un backup dentro de una politica cuando no se nombra uno concreto.
 */
public enum BackupSelectorMode {

    /** La copia correcta mas reciente. */
    Latest,

    /** La copia correcta mas reciente anterior a un instante dado. */
    Before
}
