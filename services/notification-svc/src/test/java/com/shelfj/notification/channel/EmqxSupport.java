package com.shelfj.notification.channel;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Starts the same EMQX broker + config the docker-compose stack uses (infra/emqx.conf,
 * infra/emqx-acl.conf) so {@link MqttAclIT} exercises the real JWT-auth + ACL rules, not a
 * stand-in.
 * Mounts those two files straight from the repo (no copy) so the test can never drift from what
 * actually ships — this couples the test to running from the notification-svc module directory
 * (the normal `mvn test` working directory), which is where every other module-relative path in
 * this repo already assumes it runs from.
 */
final class EmqxSupport implements AutoCloseable {

  private static final int MQTT_PORT = 1883;
  private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().resolve("../..").normalize();

  private final GenericContainer<?> container;

  private EmqxSupport(GenericContainer<?> container) {
    this.container = container;
  }

  static EmqxSupport start(String jwtSecret) {
    @SuppressWarnings("resource")
    GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.8.0"))
            .withExposedPorts(MQTT_PORT)
            .withEnv("SHELFJ_JWT_SECRET", jwtSecret)
            .withCopyFileToContainer(
                MountableFile.forHostPath(REPO_ROOT.resolve("infra/emqx.conf")),
                "/opt/emqx/etc/emqx.conf")
            .withCopyFileToContainer(
                MountableFile.forHostPath(REPO_ROOT.resolve("infra/emqx-acl.conf")),
                "/opt/emqx/etc/acl.conf")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(90));
    c.start();
    return new EmqxSupport(c);
  }

  String host() {
    return container.getHost();
  }

  int port() {
    return container.getMappedPort(MQTT_PORT);
  }

  void stop() {
    container.stop();
  }

  @Override
  public void close() {
    stop();
  }
}
