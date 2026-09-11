package io.pgvault.operator.schedule;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Que hacer en este instante con una politica: disparar, descartar o esperar.
 *
 * <p>Funcion pura de cuatro entradas y ninguna dependencia. El reconciler decide
 * mirando solo esto, asi que la pregunta dificil, que hacer cuando el operator
 * lleva dos horas apagado, se contesta sin clientes, sin relojes escondidos y
 * con una prueba unitaria.
 *
 * @param due     disparo que toca ejecutar ahora, o null si no hay ninguno
 * @param next    proximo disparo futuro, para reprogramar la reconciliacion
 * @param expired disparo que se descarto por llegar tarde a su ventana
 */
public record ScheduleWindow(ZonedDateTime due, ZonedDateTime next, ZonedDateTime expired) {

    /**
     * Evalua la politica contra el reloj.
     *
     * <p>Solo se mira el ultimo disparo que ya paso, nunca la lista de todos los
     * que se perdieron. Es lo que hace que un operator que estuvo dos horas caido
     * arranque una copia al volver, y no sesenta: nadie quiere sesenta volcados
     * simultaneos contra la misma base a modo de bienvenida, y cincuenta y nueve
     * de esas copias quedarian obsoletas en cuanto termine la ultima.
     *
     * @param lastProcessed ultimo disparo que la politica ya proceso, o el
     *                      instante en que se creo si todavia no proceso ninguno
     * @param deadline      antiguedad maxima de un disparo pendiente para que
     *                      todavia merezca la pena ejecutarlo, o null para
     *                      ejecutarlo por viejo que sea
     */
    public static ScheduleWindow evaluate(CronExpression cron,
                                          ZonedDateTime lastProcessed,
                                          ZonedDateTime now,
                                          Duration deadline) {

        ZonedDateTime next = cron.nextAfter(now);
        ZonedDateTime previous = cron.previousAtOrBefore(now);

        if (previous == null || !previous.isAfter(lastProcessed)) {
            return new ScheduleWindow(null, next, null);
        }

        if (deadline != null && previous.isBefore(now.minus(deadline))) {
            return new ScheduleWindow(null, next, previous);
        }

        return new ScheduleWindow(previous, next, null);
    }
}
