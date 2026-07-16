package com.shelfj.config;

import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Serves per-service, per-profile configuration as JSON.
 *
 * <p>{@code GET /config/{service}/{profile}} returns the merged key/values a service should
 * bootstrap with. Phase-0 backend reads {@code <repo>/{service}-{profile}.properties} (and falls
 * back to {@code {service}.properties}). Production swaps the backend for Git or Consul-KV behind
 * this same API.
 *
 * <p>Secrets do NOT live in the committed config repo — they come from the environment / a secret
 * store at deploy time (golden rule #5). This service is for non-secret, environment-shaped
 * configuration.
 */
@Path("/config")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Config")
public class ConfigResource {

  @Inject
  @ConfigProperty(name = "shelfj.config.repo", defaultValue = "config-repo")
  String repoDir;

  /** Service/profile names are used to build file paths — keep them to a strict safe charset. */
  private static final java.util.regex.Pattern SAFE_NAME =
      java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

  @Operation(
      summary = "Get merged configuration for a service/profile",
      description =
          "Merges {service}.properties with the {service}-{profile}.properties overlay. Internal"
              + " use only — called by services at bootstrap, not reachable through the gateway.")
  @APIResponse(responseCode = "200", description = "Merged key/value configuration")
  @APIResponse(responseCode = "400", description = "service or profile name outside [A-Za-z0-9_-]")
  @APIResponse(responseCode = "404", description = "No configuration found for service/profile")
  @GET
  @Path("/{service}/{profile}")
  public ApiResponse<Map<String, String>> get(
      @PathParam("service") String service, @PathParam("profile") String profile) {
    requireSafeName(service, "service");
    requireSafeName(profile, "profile");
    var merged = new LinkedHashMap<String, String>();
    // base (service.properties) then profile overlay (service-profile.properties)
    loadInto(merged, service + ".properties");
    loadInto(merged, service + "-" + profile + ".properties");
    if (merged.isEmpty()) {
      throw ApiException.notFound(
          "CONFIG_NOT_FOUND", "No configuration for service=" + service + " profile=" + profile);
    }
    return ApiResponse.ok(merged);
  }

  /**
   * Rejects anything outside {@code [A-Za-z0-9_-]} — path separators, dots, and encoded traversal
   * sequences can otherwise escape the config repo and read arbitrary *.properties on disk.
   */
  private static void requireSafeName(String value, String what) {
    if (value == null || !SAFE_NAME.matcher(value).matches()) {
      throw ApiException.badRequest(
          "CONFIG_INVALID_NAME", what + " must match [A-Za-z0-9_-]{1,64}");
    }
  }

  private void loadInto(Map<String, String> target, String fileName) {
    Properties props = new Properties();
    // Prefer a file in the configured repo dir; fall back to a bundled classpath resource.
    java.nio.file.Path filePath = java.nio.file.Path.of(repoDir, fileName);
    try {
      if (Files.isReadable(filePath)) {
        try (var in = Files.newInputStream(filePath)) {
          props.load(in);
        }
      } else {
        try (InputStream in =
            Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("config-repo/" + fileName)) {
          if (in != null) {
            props.load(in);
          }
        }
      }
    } catch (IOException e) {
      throw new ApiException(
          500, "CONFIG_READ_ERROR", "Failed to read " + fileName, java.util.List.of(), e);
    }
    props.forEach((k, v) -> target.put(String.valueOf(k), String.valueOf(v)));
  }
}
