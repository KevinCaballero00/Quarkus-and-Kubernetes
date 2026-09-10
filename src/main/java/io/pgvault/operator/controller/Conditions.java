package io.pgvault.operator.controller;

import io.fabric8.kubernetes.api.model.Condition;

import java.time.Instant;
import java.util.List;

/**
 * Manipulacion de conditions con la semantica que espera Kubernetes.
 *
 * <p>El detalle que casi todo el mundo se salta es lastTransitionTime: solo debe
 * cambiar cuando cambia el status de la condition, nunca en cada reconciliacion.
 * Si se reescribe siempre, el campo deja de significar "desde cuando esta asi" y
 * pasa a significar "cuando fue la ultima vez que miramos", que no le sirve a
 * nadie. Ademas provoca una escritura en cada pasada, y con ella un evento de
 * modificacion que dispara otra reconciliacion.
 */
public final class Conditions {

    public static final String READY = "Ready";
    public static final String SCHEDULED = "Scheduled";
    public static final String STORAGE_REACHABLE = "StorageReachable";
    public static final String SOURCE_REACHABLE = "SourceReachable";

    private Conditions() {
    }

    /**
     * Inserta la condition o actualiza la existente del mismo tipo.
     *
     * @param observedGeneration generacion del spec que se estaba mirando, para
     *                           que quien lea el status sepa si corresponde a su
     *                           ultimo cambio o a uno anterior
     */
    public static void set(List<Condition> conditions,
                           String type,
                           boolean ok,
                           String reason,
                           String message,
                           Long observedGeneration) {

        String status = ok ? "True" : "False";

        for (Condition existing : conditions) {
            if (type.equals(existing.getType())) {
                if (!status.equals(existing.getStatus())) {
                    existing.setLastTransitionTime(Instant.now().toString());
                }
                existing.setStatus(status);
                existing.setReason(reason);
                existing.setMessage(message);
                existing.setObservedGeneration(observedGeneration);
                return;
            }
        }

        Condition added = new Condition();
        added.setType(type);
        added.setStatus(status);
        added.setReason(reason);
        added.setMessage(message);
        added.setObservedGeneration(observedGeneration);
        added.setLastTransitionTime(Instant.now().toString());
        conditions.add(added);
    }
}
