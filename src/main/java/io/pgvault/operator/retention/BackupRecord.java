package io.pgvault.operator.retention;

import java.time.Instant;

/**
 * Lo unico que la retencion necesita saber de una copia.
 *
 * <p>Tres campos en vez del recurso entero, y no es purismo: obliga a que el
 * algoritmo no pueda mirar nada mas, ni el cliente de Kubernetes, ni el reloj,
 * ni el estado del bucket. Lo que no se puede tocar no se puede enredar, y lo
 * que no se enreda se prueba con una lista escrita a mano.
 *
 * @param completedAt instante en que termino, que es el que ordena
 * @param succeeded   si la ejecucion dejo una copia utilizable
 */
public record BackupRecord(String name, Instant completedAt, boolean succeeded) {
}
