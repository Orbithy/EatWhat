package you.v50to.eatwhat.integration;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

/** Test-owned containers only: no host database, fixed port, reusable volume or external credentials. */
final class IsolatedContainers {
    static final String DATABASE = "eatwhat_test";
    static final String USER = "eatwhat_test";
    static final String PASSWORD = "synthetic-test-only";
    static final String RUSTFS_ACCESS_KEY = "synthetic-rustfs-access";
    static final String RUSTFS_SECRET_KEY = "synthetic-rustfs-secret-only";

    private IsolatedContainers() {
    }

    static GenericContainer<?> postgres(String purpose) {
        return localContainer("postgis/postgis:16-3.5", purpose + "-pg", 5432)
                // This pinned PostGIS image publishes amd64; Docker Desktop can emulate it on ARM.
                .withCreateContainerCmdModifier(command -> command.withPlatform("linux/amd64"))
                .withEnv("POSTGRES_DB", DATABASE)
                .withEnv("POSTGRES_USER", USER)
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                // The PostGIS image also starts a temporary server while initializing its database.
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    /** Docker Desktop can publish an emulated container's port after Testcontainers' fixed five-second inspect window. */
    static GenericContainer<?> startedPostgresWithInspectRetry(String purpose) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            GenericContainer<?> container = postgres(purpose);
            try {
                container.start();
                return container;
            } catch (ContainerLaunchException failure) {
                container.close();
                if (attempt == 3 || !inspectWindowExpired(failure)) throw failure;
            }
        }
        throw new IllegalStateException("Unreachable PostgreSQL startup retry state");
    }

    private static boolean inspectWindowExpired(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getName().endsWith("ConditionTimeoutException")
                    && String.valueOf(cause.getMessage()).contains("within 5 seconds")) return true;
        }
        return false;
    }

    static GenericContainer<?> redis(String purpose) {
        return localContainer("redis:7.4-alpine", purpose + "-redis", 6379)
                .withCommand("redis-server", "--save", "", "--appendonly", "no")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
    }

    static GenericContainer<?> rustfs(String purpose) {
        return localContainer("rustfs/rustfs:latest", purpose + "-rustfs", 9000)
                .withEnv("RUSTFS_ACCESS_KEY", RUSTFS_ACCESS_KEY)
                .withEnv("RUSTFS_SECRET_KEY", RUSTFS_SECRET_KEY)
                .withEnv("RUSTFS_ADDRESS", ":9000")
                .withEnv("RUSTFS_CONSOLE_ADDRESS", ":9001")
                .withEnv("RUSTFS_CONSOLE_ENABLE", "true")
                .withCommand("/data")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    static String jdbcUrl(GenericContainer<?> postgres, String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + database + "?connectTimeout=5&socketTimeout=10";
    }

    private static GenericContainer<?> localContainer(String image, String purpose, int port) {
        String name = "eatwhat-test-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
        return new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(port)
                .withLabel("eatwhat.test", "integration")
                .withReuse(false)
                .withCreateContainerCmdModifier(command -> {
                    command.withName(name);
                    command.getHostConfig().withPortBindings(new PortBinding(
                            Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(port)));
                });
    }
}
