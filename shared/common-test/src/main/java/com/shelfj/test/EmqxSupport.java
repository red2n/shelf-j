package com.shelfj.test;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
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
  private final HttpServer jwks;

  private EmqxSupport(GenericContainer<?> container, HttpServer jwks) {
    this.container = container;
    this.jwks = jwks;
  }

  /** The username the ACL lets publish to every business's topics (infra/emqx-acl.conf). */
  public static final String PUBLISHER = "__publisher__";

  /**
   * Starts the broker as the stack configures it (20.15): a device presents a platform token, which
   * the broker verifies against a published key set; the publisher presents its own password. The
   * key set is served from this JVM and reached from the container over Testcontainers' host port.
   *
   * @param jwksJson the key set the broker fetches (what iam-svc publishes)
   * @param publisherPassword the publisher's password, loaded from the broker's bootstrap file
   * @return the started broker
   */
  public static EmqxSupport start(String jwksJson, String publisherPassword) {
    return start(() -> jwksJson, publisherPassword);
  }

  /**
   * The same, for a key set not known when the broker starts — iam-svc makes its first key when it
   * issues its first token. The broker re-reads the key set every five seconds.
   *
   * @param jwksJson what to serve each time the broker asks
   */
  public static EmqxSupport start(
      java.util.function.Supplier<String> jwksJson, String publisherPassword) {
    HttpServer jwks;
    try {
      jwks = HttpServer.create(new InetSocketAddress(0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    jwks.createContext(
        "/",
        ex -> {
          byte[] body = jwksJson.get().getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    jwks.start();
    int jwksPort = jwks.getAddress().getPort();
    org.testcontainers.Testcontainers.exposeHostPorts(jwksPort);

    @SuppressWarnings("resource")
    GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.8.0"))
            .withExposedPorts(MQTT_PORT, API_PORT)
            .withAccessToHost(true)
            // infra/emqx.conf names iam-svc's key set; EMQX only reads its own EMQX_<PATH>
            // overrides, so the test points the second authenticator at this JVM instead.
            .withEnv(
                "EMQX_AUTHENTICATION__2__ENDPOINT",
                "http://host.testcontainers.internal:" + jwksPort + "/jwks.json")
            .withEnv("EMQX_AUTHENTICATION__2__REFRESH_INTERVAL", "5")
            .withCopyFileToContainer(
                MountableFile.forHostPath(REPO_ROOT.resolve("infra/emqx.conf")),
                "/opt/emqx/etc/emqx.conf")
            .withCopyFileToContainer(
                MountableFile.forHostPath(REPO_ROOT.resolve("infra/emqx-acl.conf")),
                "/opt/emqx/etc/acl.conf")
            .withCopyFileToContainer(
                MountableFile.forHostPath(REPO_ROOT.resolve("infra/emqx-api-key.conf")),
                "/opt/emqx/etc/api-key.conf")
            .withCopyToContainer(
                Transferable.of(
                    "user_id,password,is_superuser\n"
                        + PUBLISHER
                        + ","
                        + publisherPassword
                        + ",false\n"),
                "/opt/emqx/etc/auth-bootstrap.csv")
            // Wait.forListeningPort() (a host-side TCP probe of the mapped port) proved
            // unreliable in some sandboxed Docker environments even when the broker was
            // confirmed up and reachable by every other means (docker logs, docker port, a
            // standalone java.net.Socket connect) — matching the exact banner line EMQX prints
            // on success reads container stdout via the Docker API instead, sidestepping that.
            .waitingFor(Wait.forLogMessage(".*EMQX .* is running now!.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(90));
    c.start();
    return new EmqxSupport(c, jwks);
  }

  public String host() {
    return container.getHost();
  }

  /**
   * @return the host-mapped MQTT port (not necessarily {@value #MQTT_PORT})
   */
  public int port() {
    return container.getMappedPort(MQTT_PORT);
  }

  /**
   * HTTP Management API (kick a client, etc.) — see {@code infra/emqx-api-key.conf}.
   *
   * @return the host-mapped management API port (not necessarily {@value #API_PORT})
   */
  public int apiPort() {
    return container.getMappedPort(API_PORT);
  }

  /** Stops and removes the container. */
  public void stop() {
    container.stop();
    jwks.stop(0);
  }

  @Override
  public void close() {
    stop();
  }
}
