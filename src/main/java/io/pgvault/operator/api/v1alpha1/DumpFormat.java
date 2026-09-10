package io.pgvault.operator.api.v1alpha1;

/**
 * Formato de salida de pg_dump.
 *
 * <p>Custom es el predeterminado porque es el unico que permite restaurar de
 * forma selectiva y en paralelo con pg_restore. Plain produce SQL legible pero
 * solo se puede reproducir entero y en orden.
 */
public enum DumpFormat {

    Plain("p", ".sql"),
    Custom("c", ".dump"),
    Directory("d", ".dir");

    private final String pgDumpFlag;
    private final String fileSuffix;

    DumpFormat(String pgDumpFlag, String fileSuffix) {
        this.pgDumpFlag = pgDumpFlag;
        this.fileSuffix = fileSuffix;
    }

    public String pgDumpFlag() {
        return pgDumpFlag;
    }

    public String fileSuffix() {
        return fileSuffix;
    }
}
