package io.pgvault.operator.api.v1alpha1;

/**
 * Compresion aplicada al volcado antes de subirlo.
 *
 * <p>El sufijo forma parte del contrato: la clave del objeto en el bucket lo
 * lleva, y quien restaure a mano necesita saber con que descomprimir sin
 * consultar el CR que lo genero.
 */
public enum CompressionAlgorithm {

    None(""),
    Gzip(".gz"),
    Zstd(".zst");

    private final String fileSuffix;

    CompressionAlgorithm(String fileSuffix) {
        this.fileSuffix = fileSuffix;
    }

    public String fileSuffix() {
        return fileSuffix;
    }
}
