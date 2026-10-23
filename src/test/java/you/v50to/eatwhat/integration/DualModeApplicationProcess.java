package you.v50to.eatwhat.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Starts only the test entry point, with explicit synthetic config and no inherited application environment. */
final class DualModeApplicationProcess implements AutoCloseable {
    private static final Duration STARTUP_LIMIT = Duration.ofSeconds(75);
    private final Process process;
    private final Path log;
    private final long startedAt = System.nanoTime();
    private JsonNode ready;

    DualModeApplicationProcess(Path workingRoot, String name, Map<String, String> properties) throws IOException {
        Path directory = Files.createDirectory(workingRoot.resolve(name));
        log = directory.resolve("application.log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String absoluteClasspath = Arrays.stream(classpath.split(File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xms64m");
        command.add("-Xmx384m");
        command.add("-cp");
        command.add(absoluteClasspath);
        command.add(DualModeTestApplication.class.getName());
        command.add("--spring.config.location=classpath:/application-integration.properties");
        command.add("--spring.config.additional-location=");
        command.add("--spring.config.import=");
        properties.forEach((key, value) -> command.add("--" + key + "=" + value));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // Do not inherit SPRING_*, AWS_*, SENTRY_*, JAVA_TOOL_OPTIONS or any host secret.
        builder.environment().clear();
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("TZ", "UTC");
        process = builder.start();
    }

    JsonNode awaitReady(ObjectMapper mapper) throws Exception {
        while (System.nanoTime() - startedAt < STARTUP_LIMIT.toNanos()) {
            String text = readLog();
            for (String line : text.lines().toList()) {
                if (line.startsWith(DualModeTestApplication.READY_PREFIX)) {
                    ready = mapper.readTree(line.substring(DualModeTestApplication.READY_PREFIX.length()));
                    if (!process.isAlive()) {
                        throw failure("Child exited after declaring readiness");
                    }
                    return ready;
                }
            }
            if (!process.isAlive()) {
                throw failure("Child failed before readiness, exit=" + process.exitValue());
            }
            Thread.sleep(100);
        }
        throw failure("Child startup exceeded " + STARTUP_LIMIT);
    }

    String awaitStartupFailure() throws Exception {
        long remaining = STARTUP_LIMIT.toNanos() - (System.nanoTime() - startedAt);
        if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
            throw failure("Invalid local configuration did not fail startup within " + STARTUP_LIMIT);
        }
        String text = readLog();
        if (process.exitValue() == 0 || text.contains(DualModeTestApplication.READY_PREFIX)) {
            throw failure("Invalid local configuration unexpectedly started successfully");
        }
        return text;
    }

    int port() {
        if (ready == null) {
            throw new IllegalStateException("awaitReady must succeed before issuing HTTP requests");
        }
        return ready.path("port").asInt();
    }

    long pid() {
        return process.pid();
    }

    String recentLog() {
        try {
            String text = readLog();
            return text.substring(Math.max(0, text.length() - 12_000));
        } catch (IOException failure) {
            return "<unable to read child log: " + failure.getMessage() + ">";
        }
    }

    private String readLog() throws IOException {
        return Files.exists(log) ? Files.readString(log) : "";
    }

    private AssertionError failure(String message) throws IOException {
        String text = readLog();
        return new AssertionError(message + "; log=" + log + "\n"
                + text.substring(Math.max(0, text.length() - 8000)));
    }

    @Override
    public void close() throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Child process did not terminate: " + process.pid());
            }
        }
    }
}
