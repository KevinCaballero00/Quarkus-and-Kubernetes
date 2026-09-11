package io.pgvault.operator.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Locale;

/**
 * Expresion cron de cinco campos, con el calculo del disparo anterior y del
 * siguiente.
 *
 * <p>Sin dependencias y sin estado: se parsea una vez y responde preguntas sobre
 * cualquier instante. Esa pureza es el motivo de que el planificador viva en el
 * operator y no en CronJobs de Kubernetes, porque permite responder "cuando toca
 * la proxima" sin crear nada ni preguntarle a nadie, y hace que todo esto se
 * pueda probar sin un cluster delante.
 *
 * <p>Gramatica soportada: {@code *}, numeros, rangos {@code a-b}, listas
 * {@code a,b,c} y pasos {@code *&#47;n} o {@code a-b&#47;n}, mas las macros
 * {@code @hourly}, {@code @daily}, {@code @weekly}, {@code @monthly} y
 * {@code @yearly}. No hay nombres de mes ni de dia, ni los comodines {@code L} y
 * {@code W} de Quartz: el patron del CRD ya restringe la entrada a esta misma
 * gramatica, asi que aceptar mas aqui solo serviria para que el operator
 * entendiera cosas que el servidor de API rechaza antes.
 */
public final class CronExpression {

    /**
     * Tope de saltos en la busqueda. Cada salto avanza al menos un minuto y como
     * mucho un mes, asi que esto cubre mas de un siglo de calendario. Existe para
     * que un cron imposible como {@code 0 3 31 2 *}, el 31 de febrero, termine en
     * un error claro en vez de en un bucle infinito dentro del reconciler.
     */
    private static final int MAX_STEPS = 5000;

    private final String expression;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;

    private CronExpression(String expression, BitSet minutes, BitSet hours, BitSet daysOfMonth,
                           BitSet months, BitSet daysOfWeek,
                           boolean dayOfMonthRestricted, boolean dayOfWeekRestricted) {
        this.expression = expression;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthRestricted = dayOfMonthRestricted;
        this.dayOfWeekRestricted = dayOfWeekRestricted;
    }

    /**
     * @throws IllegalArgumentException si la expresion no se puede interpretar,
     *                                  con un mensaje pensado para acabar en una
     *                                  condition del status
     */
    public static CronExpression parse(String expression) {

        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("El schedule esta vacio.");
        }

        String normalized = expand(expression.strip());
        String[] fields = normalized.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException(
                    "Un cron tiene cinco campos (minuto hora dia mes dia-semana) y este tiene "
                            + fields.length + ": " + expression);
        }

        BitSet minutes = field(fields[0], 0, 59, "minuto");
        BitSet hours = field(fields[1], 0, 23, "hora");
        BitSet daysOfMonth = field(fields[2], 1, 31, "dia del mes");
        BitSet months = field(fields[3], 1, 12, "mes");
        BitSet daysOfWeek = daysOfWeek(fields[4]);

        return new CronExpression(expression, minutes, hours, daysOfMonth, months, daysOfWeek,
                !"*".equals(fields[2]), !"*".equals(fields[4]));
    }

    private static String expand(String expression) {
        return switch (expression.toLowerCase(Locale.ROOT)) {
            case "@hourly" -> "0 * * * *";
            case "@daily", "@midnight" -> "0 0 * * *";
            case "@weekly" -> "0 0 * * 0";
            case "@monthly" -> "0 0 1 * *";
            case "@yearly", "@annually" -> "0 0 1 1 *";
            default -> expression;
        };
    }

    /**
     * El domingo se escribe 0 o 7, como en Vixie cron. Ambos valores caen en el
     * mismo bit para que {@code 0-7} no signifique ocho dias distintos.
     */
    private static BitSet daysOfWeek(String field) {
        BitSet parsed = field(field, 0, 7, "dia de la semana");
        if (parsed.get(7)) {
            parsed.set(0);
            parsed.clear(7);
        }
        return parsed;
    }

    private static BitSet field(String field, int min, int max, String name) {

        BitSet values = new BitSet(max + 1);

        for (String part : field.split(",")) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("El campo " + name + " tiene un hueco vacio: " + field);
            }

            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = number(part.substring(slash + 1), name);
                if (step < 1) {
                    throw new IllegalArgumentException(
                            "El paso del campo " + name + " tiene que ser mayor que cero: " + part);
                }
            }

            int from;
            int to;
            if ("*".equals(range)) {
                from = min;
                to = max;
            } else {
                int dash = range.indexOf('-');
                if (dash >= 0) {
                    from = number(range.substring(0, dash), name);
                    to = number(range.substring(dash + 1), name);
                } else {
                    from = number(range, name);
                    // "5/15" significa desde 5 hasta el final, saltando de 15 en 15.
                    to = slash >= 0 ? max : from;
                }
            }

            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException(
                        "El campo " + name + " acepta valores de " + min + " a " + max
                                + " y recibio " + part + ".");
            }
            for (int value = from; value <= to; value += step) {
                values.set(value);
            }
        }

        if (values.isEmpty()) {
            throw new IllegalArgumentException("El campo " + name + " no selecciona ningun valor: " + field);
        }
        return values;
    }

    private static int number(String text, String name) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El campo " + name + " esperaba un numero y recibio " + text + ".");
        }
    }

    /**
     * Primer disparo estrictamente posterior al minuto de {@code from}.
     *
     * <p>La busqueda es en hora local y la conversion a instante se hace al
     * final, que es lo unico que respeta de verdad una zona horaria: una politica
     * a las tres de la manana en Bogota tiene que seguir siendo a las tres de la
     * manana aunque cambie el desfase con UTC.
     */
    public ZonedDateTime nextAfter(ZonedDateTime from) {

        LocalDateTime candidate = from.toLocalDateTime()
                .withSecond(0).withNano(0)
                .plusMinutes(1);

        for (int step = 0; step < MAX_STEPS; step++) {
            if (!months.get(candidate.getMonthValue())) {
                candidate = candidate.withDayOfMonth(1).toLocalDate().atStartOfDay().plusMonths(1);
                continue;
            }
            if (!dayMatches(candidate.toLocalDate())) {
                candidate = candidate.toLocalDate().atStartOfDay().plusDays(1);
                continue;
            }
            if (!hours.get(candidate.getHour())) {
                candidate = candidate.withMinute(0).plusHours(1);
                continue;
            }
            if (!minutes.get(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }
            return atZone(candidate, from);
        }

        throw new IllegalArgumentException(
                "El schedule " + expression + " no se cumple en ninguna fecha del calendario.");
    }

    /**
     * Ultimo disparo que ya ocurrio, contando el minuto de {@code from}.
     *
     * <p>Es lo que permite reincorporarse despues de una caida sin enumerar todo
     * lo que se perdio: se pregunta cual fue el ultimo disparo y se compara con
     * el que la politica dice haber procesado. Un operator apagado dos horas
     * necesita saber que hay algo pendiente, no cuantas veces lo estuvo.
     */
    public ZonedDateTime previousAtOrBefore(ZonedDateTime from) {

        LocalDateTime candidate = from.toLocalDateTime().withSecond(0).withNano(0);

        for (int step = 0; step < MAX_STEPS; step++) {
            if (!months.get(candidate.getMonthValue())) {
                candidate = candidate.withDayOfMonth(1).toLocalDate().atStartOfDay().minusMinutes(1);
                continue;
            }
            if (!dayMatches(candidate.toLocalDate())) {
                candidate = candidate.toLocalDate().atStartOfDay().minusMinutes(1);
                continue;
            }
            if (!hours.get(candidate.getHour())) {
                candidate = candidate.withMinute(0).minusMinutes(1);
                continue;
            }
            if (!minutes.get(candidate.getMinute())) {
                candidate = candidate.minusMinutes(1);
                continue;
            }
            return atZone(candidate, from);
        }

        return null;
    }

    /**
     * Regla de Vixie cron: si el dia del mes y el dia de la semana estan los dos
     * restringidos, basta con que se cumpla uno. Es contraintuitivo y es lo que
     * hace que {@code 0 0 1,15 * 5} signifique "el 1, el 15 y todos los viernes".
     */
    private boolean dayMatches(LocalDate date) {
        boolean dayOfMonthOk = daysOfMonth.get(date.getDayOfMonth());
        boolean dayOfWeekOk = daysOfWeek.get(date.getDayOfWeek().getValue() % 7);

        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            return dayOfMonthOk || dayOfWeekOk;
        }
        if (dayOfMonthRestricted) {
            return dayOfMonthOk;
        }
        if (dayOfWeekRestricted) {
            return dayOfWeekOk;
        }
        return true;
    }

    /**
     * Una hora local puede no existir o existir dos veces el dia que cambia el
     * horario de verano. {@code ZonedDateTime.of} adelanta la que no existe al
     * otro lado del salto y se queda con la primera de las repetidas, que es lo
     * que hace tambien el controlador de CronJob de Kubernetes: un disparo
     * perdido es peor que uno movido una hora.
     */
    private static ZonedDateTime atZone(LocalDateTime local, ZonedDateTime reference) {
        return ZonedDateTime.of(local, reference.getZone());
    }

    @Override
    public String toString() {
        return expression;
    }
}
