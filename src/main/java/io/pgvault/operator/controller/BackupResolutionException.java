package io.pgvault.operator.controller;

/**
 * La ejecucion no se puede resolver: falta la politica referenciada, o le falta
 * un campo que el Backup necesitaba heredar.
 *
 * <p>Es un fallo del usuario, no del operator, asi que no tiene sentido
 * reintentarlo eternamente en silencio. Se lanza para que
 * {@code updateErrorStatus} lo traduzca a una condition con la causa escrita,
 * que es donde la va a buscar quien aplico el YAML.
 */
public class BackupResolutionException extends RuntimeException {

    public BackupResolutionException(String message) {
        super(message);
    }
}
