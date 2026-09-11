package io.pgvault.operator.retention;

import io.pgvault.operator.api.v1alpha1.RetentionPolicy;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Decide que copias sobran.
 *
 * <p>Funcion pura: entra una lista de copias y una politica, sale la lista de
 * las que hay que borrar. Sin cliente de Kubernetes, sin reloj propio y sin
 * saber que existe un bucket. Lo unico que hace el reconciler con el resultado
 * es borrar recursos, y el finalizer de cada uno se lleva su objeto por delante.
 * Esa separacion es la que evita el problema clasico de las herramientas de
 * copias: dos inventarios, el de la API y el del almacenamiento, que se
 * desincronizan en cuanto alguien borra algo a mano.
 *
 * <p><b>Los criterios de conservacion se suman.</b> Una copia sobrevive si la
 * salva cualquiera de ellos, asi que poner un {@code keepLast} bajo no se lleva
 * por delante las semanales. {@code maxAge} es el unico que resta: es un techo
 * de edad que se aplica despues y que gana a los demas, porque "descarta lo mas
 * viejo que esto" no admite excepciones que lo contradigan.
 *
 * <p><b>Salvo una.</b> La copia correcta mas reciente no se borra nunca, ni
 * siquiera cuando {@code maxAge} dice que ya es vieja. Una herramienta de copias
 * que se queda sin ninguna copia por una regla mal escrita ha fallado en lo
 * unico que tenia que hacer. Si lo que se busca es un techo legal de retencion,
 * y no una politica de espacio, esa ultima copia hay que borrarla a mano y a
 * conciencia.
 */
public final class Retention {

    private Retention() {
    }

    /**
     * @param zone zona horaria de la politica, que es la que define donde
     *             empieza un dia. Agrupar en UTC partiria las copias nocturnas
     *             de media Europa y America por la mitad del dia equivocado.
     * @return las copias a borrar, de la mas reciente a la mas antigua
     */
    public static List<BackupRecord> selectForDeletion(List<BackupRecord> backups,
                                                       RetentionPolicy policy,
                                                       ZoneId zone,
                                                       Instant now) {

        // Sin politica de retencion no se borra nada nunca. Es la unica opcion
        // segura por defecto: el coste de guardar de mas es dinero, y el de
        // borrar de menos es no tener la copia el dia que hace falta.
        if (policy == null || backups.isEmpty()) {
            return List.of();
        }

        List<BackupRecord> newestFirst = backups.stream()
                .sorted(Comparator.comparing(BackupRecord::completedAt).reversed())
                .toList();
        List<BackupRecord> usable = newestFirst.stream()
                .filter(BackupRecord::succeeded)
                .toList();

        Set<BackupRecord> keep = new LinkedHashSet<>();

        boolean anyKeepRule = policy.getKeepLast() != null
                || policy.getKeepDaily() != null
                || policy.getKeepWeekly() != null
                || policy.getKeepMonthly() != null;

        if (!anyKeepRule) {
            // Solo hay maxAge. Entonces el conjunto a conservar es todo, y el
            // techo de edad decide. Empezar con el conjunto vacio aqui seria
            // borrarlo absolutamente todo.
            keep.addAll(usable);
        } else {
            if (policy.getKeepLast() != null) {
                usable.stream().limit(policy.getKeepLast()).forEach(keep::add);
            }
            keepOnePerPeriod(usable, policy.getKeepDaily(), zone,
                    moment -> moment.toLocalDate(), keep);
            keepOnePerPeriod(usable, policy.getKeepWeekly(), zone,
                    moment -> moment.get(IsoFields.WEEK_BASED_YEAR)
                            + "-" + moment.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), keep);
            keepOnePerPeriod(usable, policy.getKeepMonthly(), zone,
                    YearMonth::from, keep);
        }

        // La ultima ejecucion fallida se conserva: es la que explica por que no
        // hay una copia mas reciente, y borrarla dejaria el problema invisible.
        // Las anteriores a esa no cuentan nada que esta no cuente ya.
        newestFirst.stream()
                .filter(record -> !record.succeeded())
                .findFirst()
                .ifPresent(keep::add);

        if (policy.getMaxAge() != null) {
            Instant cutoff = now.minus(parseAge(policy.getMaxAge()));
            keep.removeIf(record -> record.completedAt().isBefore(cutoff));
        }

        usable.stream().findFirst().ifPresent(keep::add);

        return newestFirst.stream()
                .filter(record -> !keep.contains(record))
                .toList();
    }

    /**
     * Conserva la copia mas reciente de cada uno de los ultimos N periodos que
     * tengan alguna.
     *
     * <p>Cuentan los periodos con copias, no los del calendario. Con backups
     * diarios y una semana de vacaciones del cluster, {@code keepDaily: 7}
     * conserva siete copias, no las tres que quedaron dentro de los ultimos
     * siete dias naturales.
     */
    private static void keepOnePerPeriod(List<BackupRecord> newestFirst,
                                         Integer periods,
                                         ZoneId zone,
                                         Function<ZonedDateTime, Object> periodOf,
                                         Set<BackupRecord> keep) {
        if (periods == null) {
            return;
        }
        Set<Object> seen = new LinkedHashSet<>();
        for (BackupRecord record : newestFirst) {
            Object period = periodOf.apply(record.completedAt().atZone(zone));
            if (seen.contains(period)) {
                continue;
            }
            if (seen.size() == periods) {
                break;
            }
            seen.add(period);
            keep.add(record);
        }
    }

    /** Traduce 30d, 12h o 4w a una duracion. El patron del CRD ya filtro la forma. */
    static Duration parseAge(String maxAge) {
        long amount = Long.parseLong(maxAge.substring(0, maxAge.length() - 1));
        return switch (maxAge.charAt(maxAge.length() - 1)) {
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            case 'w' -> Duration.ofDays(7 * amount);
            default -> throw new IllegalArgumentException(
                    "maxAge se escribe como 30d, 12h o 4w, y llego " + maxAge + ".");
        };
    }
}
