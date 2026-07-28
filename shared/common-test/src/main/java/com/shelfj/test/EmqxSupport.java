package com.shelfj.test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Starts the same EMQX broker + config the docker-compose stack uses (infra/emqx.conf,
 * infra/emqx-acl.conf, infra/emqx-api-key.conf) so integration tests in notification-svc (MQTT
 * publish/ACL) and iam-svc (session-kick on logout) exercise the real broker config, not a
 * stand-in. Mounts those files straight from the repo (no copy) so a test can never drift from what
 * actually ships — this couples the test to running from a {@code services/<name>} module directory
 * (the normal `mvn test` working directory), which every other module-relative path in this repo
 * already assumes.
 */
public final class EmqxSupport implements AutoCloseable {

  private static final int MQTT_PORT = 1883;
  private static final int API_PORT = 18083;
  private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().resolve("../..").normalize();

  private final GenericContainer<?> container;

  private EmqxSupport(GenericContainer<?> container) {
    this.container = container;
  }

  public static EmqxSupport start(String jwtSecret) {
    @SuppressWarnings("resource")
    GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.8.0"))
            .withExposedPorts(MQTT_PORT, API_PORT)
            .withEnv("SHELFJ_JWT_SECRET", jwtSecret)
            .withCopyFileToContainer(
                MountableFile.forHostPath(REPO_ROOT.resolve("infra/emqx.conf")),
                "/opt/emqx/etc/emqx.conf")
            .withCopyFileToContainer(
                MountableFile.forHostPath(REPO_ROOT.resolve("infra/emqx-acl.conf")),
                "/opt/emqx/etc/acl.conf")
            .withCopyFileToContainer(
                MountableFile.forHostPath(REPO_ROOT.resolve("infra/emqx-api-key.conf")),
                "/opt/emqx/etc/api-key.conf")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(90));
    c.start();
    return new EmqxSupport(c);
  }

  public String host() {
    return container.getHost();
  }

  public int port() {
    return container.getMappedPort(MQTT_PORT);
  }

  /** HTTP Management API (kick a client, etc.) — see {@code infra/emqx-api-key.conf}. */
  public int apiPort() {
    return container.getMappedPort(API_PORT);
  }

  public void stop() {
    container.stop();
  }

  @Override
  public void close() {
    stop();
  }
}
