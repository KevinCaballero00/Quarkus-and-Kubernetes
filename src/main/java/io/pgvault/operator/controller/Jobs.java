package io.pgvault.operator.controller;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;

import java.util.List;
import java.util.Optional;

/**
 * Lectura del estado de un Job.
 *
 * <p>El veredicto se saca de las conditions, no de los contadores
 * {@code succeeded} y {@code failed}. Los contadores dicen cuantos Pods hubo de
 * cada clase, no si el Job ha terminado: con reintentos pendientes,
 * {@code failed} vale uno y el Job sigue perfectamente vivo. La condition
 * {@code Failed} solo aparece cuando el controlador de Jobs ya agoto el
 * backoffLimit, que es justo el momento en el que un backup puede declararse
 * perdido.
 */
public final class Jobs {

    private static final String COMPLETE = "Complete";
    private static final String FAILED = "Failed";

    private Jobs() {
    }

    public static boolean isComplete(Job job) {
        return condition(job, COMPLETE).isPresent();
    }

    public static Optional<JobCondition> failure(Job job) {
        return condition(job, FAILED);
    }

    public static boolean isRunning(Job job) {
        return !isComplete(job) && failure(job).isEmpty();
    }

    public static String startTime(Job job) {
        return job.getStatus() != null ? job.getStatus().getStartTime() : null;
    }

    public static String completionTime(Job job) {
        return job.getStatus() != null ? job.getStatus().getCompletionTime() : null;
    }

    private static Optional<JobCondition> condition(Job job, String type) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) {
            return Optional.empty();
        }
        List<JobCondition> conditions = job.getStatus().getConditions();
        return conditions.stream()
                .filter(c -> type.equals(c.getType()) && "True".equals(c.getStatus()))
                .findFirst();
    }
}
