package io.pgvault.operator.controller;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lo que el runner cuenta de si mismo cuando termina.
 *
 * <p>El canal es el mensaje de terminacion del contenedor: el runner escribe una
 * linea en {@code /dev/termination-log} y el kubelet la copia al status del Pod,
 * donde el operator la lee con un simple GET. La alternativa habitual es leer el
 * log del Pod, y es peor por dos razones. Obliga a dar permiso sobre
 * {@code pods/log}, que deja ver cualquier cosa que cualquier contenedor haya
 * impreso, credenciales incluidas. Y los logs se rotan y se truncan, asi que el
 * dato del que depende el status seria el mas fragil de todos.
 *
 * <p>El formato es {@code clave=valor} separado por espacios, con {@code error}
 * siempre al final porque su valor lleva espacios. Un formato de texto plano
 * tambien significa que un humano puede leer el informe con
 * {@code kubectl describe pod} sin herramientas.
 */
public record RunnerReport(String objectKey,
                           Long sizeBytes,
                           Long durationSeconds,
                           String postgresVersion,
                           String error,
                           Instant finishedAt,
                           Integer exitCode) {

    /**
     * Informe del ultimo intento del Job.
     *
     * <p>Se consulta con una lectura directa y solo cuando el Job ya termino, no
     * con un informer sobre Pods. Un informer de Pods en todo el cluster es de
     * las cosas mas caras en memoria que puede hacer un operator, y aqui no
     * aportaria nada: el evento que importa, que el Job termino, ya llega por el
     * informer de Jobs.
     */
    public static Optional<RunnerReport> lastAttempt(List<Pod> pods) {
        return pods.stream()
                .map(RunnerReport::terminatedState)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(
                        t -> parseOrEpoch(t.getFinishedAt()),
                        Comparator.naturalOrder()))
                .map(RunnerReport::parse);
    }

    private static Optional<ContainerStateTerminated> terminatedState(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return Optional.empty();
        }
        return pod.getStatus().getContainerStatuses().stream()
                .map(ContainerStatus::getState)
                .filter(state -> state != null && state.getTerminated() != null)
                .map(ContainerState::getTerminated)
                .findFirst();
    }

    private static RunnerReport parse(ContainerStateTerminated terminated) {

        String message = terminated.getMessage();
        String objectKey = null;
        Long sizeBytes = null;
        Long durationSeconds = null;
        String postgresVersion = null;
        String error = null;

        if (message != null) {
            String line = message.strip();
            int errorAt = line.indexOf("error=");
            if (errorAt >= 0) {
                error = line.substring(errorAt + "error=".length()).strip();
                line = line.substring(0, errorAt);
            }
            for (String token : line.split("\\s+")) {
                int eq = token.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = token.substring(0, eq);
                String value = token.substring(eq + 1);
                switch (key) {
                    case "objectKey" -> objectKey = value;
                    case "sizeBytes" -> sizeBytes = parseLong(value);
                    case "durationSeconds" -> durationSeconds = parseLong(value);
                    case "postgresVersion" -> postgresVersion = value;
                    default -> {
                        // Una clave que este operator no conoce viene de un runner
                        // mas nuevo. Ignorarla es lo que permite desplegar una
                        // imagen nueva sin actualizar el operator a la vez.
                    }
                }
            }
        }

        return new RunnerReport(objectKey, sizeBytes, durationSeconds, postgresVersion, error,
                parseOrEpoch(terminated.getFinishedAt()), terminated.getExitCode());
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseOrEpoch(String timestamp) {
        try {
            return timestamp != null ? Instant.parse(timestamp) : Instant.EPOCH;
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
