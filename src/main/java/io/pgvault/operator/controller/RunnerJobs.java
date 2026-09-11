package io.pgvault.operator.controller;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.pgvault.operator.api.v1alpha1.Destination;
import io.pgvault.operator.api.v1alpha1.PostgresConnection;
import io.pgvault.operator.api.v1alpha1.S3Destination;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Todo lo que comparten los Jobs que ejecutan la imagen runner.
 *
 * <p>Las credenciales se montan siempre con {@code secretKeyRef} y jamas las lee
 * el operator. Es la propiedad de seguridad que justifica que el borrado del
 * objeto en S3 tambien se haga con un Job: si el finalizer hablara con S3
 * directamente, el operator necesitaria leer los Secret de todos los namespaces
 * que observa, y las contrasenas de PostgreSQL acabarian en la memoria del
 * proceso equivocado. Asi el operator solo sabe como se llaman los Secret, nunca
 * lo que contienen.
 */
public final class RunnerJobs {

    public static final String LABEL_NAME = "app.kubernetes.io/name";
    public static final String LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String LABEL_COMPONENT = "app.kubernetes.io/component";

    /** Etiqueta que enlaza cada Pod con su Backup sin tener que seguir owner references. */
    public static final String LABEL_BACKUP = "pgvault.io/backup";

    public static final String COMPONENT_BACKUP = "backup";
    public static final String COMPONENT_CLEANUP = "cleanup";

    private static final String DEFAULT_IMAGE = "pgvault/runner:dev";
    private static final String DEFAULT_PULL_POLICY = "IfNotPresent";

    /**
     * El nombre de un Pod es el de su Job mas seis caracteres, y no puede pasar
     * de 63. De ahi el recorte.
     */
    private static final int MAX_JOB_NAME = 57;

    private RunnerJobs() {
    }

    public static String image() {
        return ConfigProvider.getConfig()
                .getOptionalValue("pgvault.runner.image", String.class)
                .orElse(DEFAULT_IMAGE);
    }

    public static String imagePullPolicy() {
        return ConfigProvider.getConfig()
                .getOptionalValue("pgvault.runner.image-pull-policy", String.class)
                .orElse(DEFAULT_PULL_POLICY);
    }

    public static String backupJobName(String backupName) {
        return derive(backupName, "-backup");
    }

    public static String cleanupJobName(String backupName) {
        return derive(backupName, "-cleanup");
    }

    /**
     * Nombre de Job derivado del nombre del Backup, recortado si hace falta.
     *
     * <p>Un recorte a secas colisionaria justo donde mas duele: los Backup que
     * genera una politica comparten prefijo y solo se distinguen por la marca de
     * tiempo del final, que es lo primero que se pierde al cortar. De ahi el
     * sufijo con el hash del nombre completo, que es estable entre ejecuciones
     * porque el de String lo fija la especificacion del lenguaje.
     */
    private static String derive(String backupName, String suffix) {
        int budget = MAX_JOB_NAME - suffix.length();
        if (backupName.length() <= budget) {
            return backupName + suffix;
        }
        String hash = Integer.toHexString(backupName.hashCode() & 0xffffff);
        return backupName.substring(0, budget - hash.length() - 1) + "-" + hash + suffix;
    }

    public static Map<String, String> labels(String backupName, String component) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_NAME, "pgvault");
        labels.put(LABEL_MANAGED_BY, "pgvault-operator");
        labels.put(LABEL_COMPONENT, component);
        labels.put(LABEL_BACKUP, backupName);
        return labels;
    }

    /**
     * Seguridad a nivel de Pod. {@code runAsNonRoot} exige que la imagen declare
     * un UID numerico: con un nombre de usuario el kubelet no puede comprobar
     * que no sea root y el Pod ni siquiera arranca.
     */
    public static PodSecurityContext podSecurityContext() {
        return new PodSecurityContextBuilder()
                .withRunAsNonRoot(true)
                .withNewSeccompProfile()
                .withType("RuntimeDefault")
                .endSeccompProfile()
                .build();
    }

    public static SecurityContext containerSecurityContext() {
        return new SecurityContextBuilder()
                .withAllowPrivilegeEscalation(false)
                .withNewCapabilities()
                .withDrop("ALL")
                .endCapabilities()
                .build();
    }

    public static ResourceRequirements resources() {
        return new ResourceRequirementsBuilder()
                .withRequests(Map.of("cpu", new Quantity("100m"), "memory", new Quantity("128Mi")))
                .withLimits(Map.of("memory", new Quantity("512Mi")))
                .build();
    }

    /** Direccion del objeto y credenciales del almacenamiento. */
    public static List<EnvVar> s3Env(Destination destination, String objectKey) {
        S3Destination s3 = destination.getS3();
        List<EnvVar> env = new ArrayList<>();
        env.add(plain("PGVAULT_S3_ENDPOINT", s3.getEndpoint()));
        env.add(plain("PGVAULT_S3_BUCKET", s3.getBucket()));
        env.add(plain("PGVAULT_OBJECT_KEY", objectKey));
        env.add(fromSecret("AWS_ACCESS_KEY_ID",
                s3.getCredentialsSecretRef().getName(),
                orDefault(s3.getCredentialsSecretRef().getAccessKeyIdKey(), "AWS_ACCESS_KEY_ID")));
        env.add(fromSecret("AWS_SECRET_ACCESS_KEY",
                s3.getCredentialsSecretRef().getName(),
                orDefault(s3.getCredentialsSecretRef().getSecretAccessKeyKey(), "AWS_SECRET_ACCESS_KEY")));
        return env;
    }

    public static List<EnvVar> postgresEnv(PostgresConnection source) {
        List<EnvVar> env = new ArrayList<>();
        env.add(plain("PGVAULT_PG_HOST", source.getHost()));
        env.add(plain("PGVAULT_PG_PORT", String.valueOf(source.getPort() != null ? source.getPort() : 5432)));
        env.add(plain("PGVAULT_PG_DATABASE", source.getDatabase()));
        env.add(plain("PGVAULT_PG_SSLMODE",
                source.getSslMode() != null ? source.getSslMode().libpqValue() : "prefer"));
        env.add(fromSecret("PGUSER",
                source.getCredentialsSecretRef().getName(),
                orDefault(source.getCredentialsSecretRef().getUsernameKey(), "username")));
        env.add(fromSecret("PGPASSWORD",
                source.getCredentialsSecretRef().getName(),
                orDefault(source.getCredentialsSecretRef().getPasswordKey(), "password")));
        return env;
    }

    public static Container container(String name, List<String> command, List<EnvVar> env) {
        ContainerBuilder builder = new ContainerBuilder()
                .withName(name)
                .withImage(image())
                .withImagePullPolicy(imagePullPolicy())
                .withEnv(env)
                .withSecurityContext(containerSecurityContext())
                .withResources(resources());

        if (command != null && !command.isEmpty()) {
            builder.withCommand(command);
        }
        return builder.build();
    }

    /** Valor de una variable de entorno del contenedor, o null si no esta. */
    public static String envValue(Container container, String name) {
        if (container == null || container.getEnv() == null) {
            return null;
        }
        return container.getEnv().stream()
                .filter(e -> name.equals(e.getName()))
                .map(EnvVar::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String orDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    public static EnvVar plainEnv(String name, String value) {
        return plain(name, value);
    }

    private static EnvVar plain(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    private static EnvVar fromSecret(String name, String secret, String key) {
        return new EnvVarBuilder()
                .withName(name)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(secret)
                .withKey(key)
                .endSecretKeyRef()
                .endValueFrom()
                .build();
    }
}
