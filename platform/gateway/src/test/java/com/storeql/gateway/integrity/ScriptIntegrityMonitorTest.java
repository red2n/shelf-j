package com.storeql.gateway.integrity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The monitor reads the shell's entry page and inventory and reports what differs (PCI DSS 11.6.1):
 * nothing for a shell that serves exactly what it lists; an unlisted script the page loads; a
 * listed script whose bytes changed; one that has gone; and a shell it cannot read is unavailable,
 * never clean.
 */
class ScriptIntegrityMonitorTest {

  private HttpServer server;
  private final Map<String, byte[]> files = new HashMap<>();
  private String base;

  @BeforeEach
  void serve() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          byte[] body = files.get(ex.getRequestURI().getPath());
          if (body == null) {
            ex.sendResponseHeaders(404, -1);
          } else {
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
              out.write(body);
            }
          }
          ex.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    put("/a.js", "console.log('a')");
    put("/b.js", "console.log('b')");
    put(
        "/index.html",
        "<html><script src=\"a.js\"></script><script src=\"/b.js\" async></script></html>");
    put(
        "/script-inventory.json",
        inventory("a.js", hash("console.log('a')"), "b.js", hash("console.log('b')")));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void put(String path, String body) {
    files.put(path, body.getBytes(StandardCharsets.UTF_8));
  }

  private static String hash(String body) {
    return ScriptIntegrityMonitor.sha256(body.getBytes(StandardCharsets.UTF_8));
  }

  private static String inventory(String... pathsAndHashes) {
    StringBuilder b = new StringBuilder("{\"scripts\":[");
    for (int i = 0; i < pathsAndHashes.length; i += 2) {
      if (i > 0) b.append(',');
      b.append("{\"path\":\"")
          .append(pathsAndHashes[i])
          .append("\",\"sha256\":\"")
          .append(pathsAndHashes[i + 1])
          .append("\",\"reason\":\"test\"}");
    }
    return b.append("]}").toString();
  }

  @Test
  void aShellThatServesWhatItListsIsClean() {
    var r = new ScriptIntegrityMonitor().check(base + "/");
    assertTrue(r.available());
    assertEquals(2, r.scripts());
    assertTrue(r.drift().isEmpty(), r.drift().toString());
    assertTrue(r.clean());
  }

  @Test
  void aScriptThePageLoadsButTheInventoryDoesNotNameIsUnlisted() {
    put(
        "/index.html",
        "<html><script src=\"a.js\"></script><script src=\"https://cdn.example/x.js\"></script><script src=\"c.js\"></script></html>");
    var r = new ScriptIntegrityMonitor().check(base);
    assertEquals(2, r.drift().size());
    assertTrue(r.drift().stream().allMatch(d -> d.kind().equals("UNLISTED")));
    assertFalse(r.clean());
  }

  @Test
  void aListedScriptWhoseBytesChangedOrWentIsDrift() {
    put("/a.js", "console.log('tampered')");
    files.remove("/b.js");
    var r = new ScriptIntegrityMonitor().check(base);
    assertEquals(2, r.drift().size());
    assertEquals("CHANGED", r.drift().get(0).kind());
    assertEquals("a.js", r.drift().get(0).path());
    assertEquals("MISSING", r.drift().get(1).kind());
    assertEquals("b.js", r.drift().get(1).path());
  }

  @Test
  void aShellThatCannotBeReadIsUnavailableNotClean() {
    files.remove("/script-inventory.json");
    var r = new ScriptIntegrityMonitor().check(base);
    assertFalse(r.available());
    assertFalse(r.clean());
    server.stop(0);
    var down = new ScriptIntegrityMonitor().check(base);
    assertFalse(down.available());
  }

  @Test
  void anUnreadableInventoryIsSaidAsSuch() {
    put("/script-inventory.json", "not json");
    var r = new ScriptIntegrityMonitor().check(base);
    assertTrue(r.available());
    assertEquals("INVENTORY_UNREADABLE", r.drift().get(0).kind());
  }
}
