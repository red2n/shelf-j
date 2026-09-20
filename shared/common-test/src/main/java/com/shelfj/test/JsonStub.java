package com.shelfj.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A stand-in for the services a service reads, in its integration tests, where discovery is off:
 * one local server answering the paths it is told to, with {@code shelfj.clients.<service>.url}
 * pointed at it for each service named. A path nobody routed is {@code 404}, as a record a real
 * service does not have is.
 *
 * <p>Start it in the test's static initialiser, before Helidon boots, and close it when the class
 * is done: closing clears the properties, so the next class does not call a server that is gone.
 */
public final class JsonStub implements AutoCloseable {

  /** A request as the stub received it: what was asked, and of whom. */
  public static final class Call {
    private final String method;
    private final String path;
    private final String query;
    private final String tenantId;
    private final String body;
    private final Map<String, String> headers;

    Call(
        String method,
        String path,
        String query,
        String tenantId,
        String body,
        Map<String, String> headers) {
      this.method = method;
      this.path = path;
      this.query = query;
      this.tenantId = tenantId;
      this.body = body;
      this.headers = Map.copyOf(headers);
    }

    public String method() {
      return method;
    }

    public String path() {
      return path;
    }

    public String query() {
      return query;
    }

    public String tenantId() {
      return tenantId;
    }

    public String body() {
      return body;
    }

    /** A request header by name, case-insensitive, or null. */
    public String header(String name) {
      return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }
  }

  /** What a route answers. */
  public record Answer(int status, String body) {

    /** {@code 200} with the envelope around {@code data}, which is JSON. */
    public static Answer ok(String data) {
      return new Answer(200, "{\"data\":" + data + "}");
    }
  }

  private final HttpServer server;
  private final List<String> services;
  private final Map<String, Function<Call, Answer>> routes = new ConcurrentHashMap<>();
  private final List<Call> calls = new CopyOnWriteArrayList<>();

  private JsonStub(HttpServer server, List<String> services) {
    this.server = server;
    this.services = services;
  }

  /**
   * Starts the stub on a free local port, standing for every service named.
   *
   * @param services the names the client properties know them by, e.g. {@code pricing-svc}
   */
  public static JsonStub start(String... services) {
    HttpServer server = serve("json-stub");
    JsonStub stub = new JsonStub(server, List.of(services));
    server.createContext("/", stub::handle);
    server.start();
    for (String service : services) {
      System.setProperty("shelfj.clients." + service + ".url", baseOf(server));
    }
    return stub;
  }

  /** Routes an exact method and path to a fixed answer. */
  public JsonStub on(String method, String path, int status, String body) {
    return on(method, path, call -> new Answer(status, body));
  }

  /** Routes an exact method and path to an answer worked out from the request. */
  public JsonStub on(String method, String path, Function<Call, Answer> answer) {
    routes.put(method + " " + path, answer);
    return this;
  }

  /** Where the stub listens, for a client configured by URL rather than by service name. */
  public String baseUrl() {
    return baseOf(server);
  }

  /** Forgets the requests received so far. */
  public void reset() {
    calls.clear();
  }

  /** Every request received so far, oldest first. */
  public List<Call> calls() {
    return List.copyOf(calls);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body;
    try (InputStream in = exchange.getRequestBody()) {
      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Map<String, String> headers = new java.util.HashMap<>();
    exchange
        .getRequestHeaders()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty())
                headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
            });
    Call call =
        new Call(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getRawQuery(),
            exchange.getRequestHeaders().getFirst("X-Tenant-Id"),
            body,
            headers);
    calls.add(call);
    Function<Call, Answer> route = routes.get(call.method() + " " + call.path());
    Answer answer =
        route == null
            ? new Answer(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"no such record\"}}")
            : route.apply(call);
    reply(exchange, answer.status(), answer.body());
  }

  /** A raw query string as its parameters, decoded; the last value wins a repeated name. */
  public static Map<String, String> query(String rawQuery) {
    Map<String, String> out = new java.util.HashMap<>();
    for (String pair : rawQuery == null ? new String[0] : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      out.put(
          java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  /** A loopback server on a free port, answering on daemon threads. */
  static HttpServer serve(String threadName) {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setExecutor(
          Executors.newCachedThreadPool(
              r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
              }));
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String baseOf(HttpServer server) {
    return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
  }

  /** The request's body, for a stub route registered directly on the server. */
  static String body(HttpExchange exchange) throws IOException {
    try (InputStream in = exchange.getRequestBody()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
    for (String service : services) {
      System.clearProperty("shelfj.clients." + service + ".url");
    }
  }
}
