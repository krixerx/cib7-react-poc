package com.poc.cib7.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * The service pack's brand as the documents see it: the portal name, the primary colour and the
 * logo as a data URI, read once from the pack's {@code branding/} folder on the classpath (the same
 * files the SPA reads at {@code /pack/branding/}). PDFs are rendered from HTML with no base URL, so
 * the logo travels inside the HTML.
 *
 * <p>Values are checked like the SPA's brand format v1 (hex colour, bare image file name), because
 * the colour lands in a {@code <style>} block where HTML escaping does not protect. A missing or
 * invalid value keeps the core default, which repeats the SPA's defaults in {@code tokens.css} and
 * {@code i18n/locales/en/brand.json}.
 */
public final class DocumentBrand {

  private static final Logger log = LoggerFactory.getLogger(DocumentBrand.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
  private static final Pattern IMAGE =
      Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}\\.(svg|png|webp)$");
  private static final Map<String, String> IMAGE_TYPES =
      Map.of("svg", "image/svg+xml", "png", "image/png", "webp", "image/webp");

  static final String DEFAULT_NAME = "eRegistrations";
  static final String DEFAULT_PRIMARY = "#0b57c9";

  private final String root;

  /** Reads from {@code <root>/} on the classpath; {@code "branding"} in the engine. */
  DocumentBrand(String root) {
    this.root = root;
  }

  /** The model under {@code brand} in every document: name, primary, logo (data URI or null). */
  Map<String, Object> model() {
    String name = DEFAULT_NAME;
    String primary = DEFAULT_PRIMARY;
    String logo = null;
    JsonNode texts = read("locales/en/brand.json");
    if (texts != null && texts.path("name").isTextual() && !texts.get("name").asText().isBlank()) {
      name = texts.get("name").asText();
    }
    JsonNode tokens = read("tokens.json");
    if (tokens != null) {
      String value = tokens.path("light").path("primary").asText("");
      if (HEX.matcher(value).matches()) {
        primary = value;
      } else if (!value.isEmpty()) {
        log.warn("branding/tokens.json light.primary is not a #rrggbb colour; using the default");
      }
    }
    JsonNode brand = read("brand.json");
    if (brand != null) {
      String file = brand.path("logo").path("light").asText("");
      if (IMAGE.matcher(file).matches()) {
        logo = dataUri(file);
      } else if (!file.isEmpty()) {
        log.warn("branding/brand.json logo.light is not a bare image file name; no logo");
      }
    }
    // A HashMap, not Map.of: the logo may be null and FreeMarker reads it with `??`.
    Map<String, Object> model = new java.util.HashMap<>();
    model.put("name", name);
    model.put("primary", primary);
    model.put("logo", logo);
    return model;
  }

  private JsonNode read(String path) {
    ClassPathResource resource = new ClassPathResource(root + "/" + path);
    if (!resource.exists()) {
      return null;
    }
    try (InputStream in = resource.getInputStream()) {
      return JSON.readTree(in);
    } catch (IOException e) {
      log.warn("branding/{} is not readable JSON; using the defaults", path, e);
      return null;
    }
  }

  private String dataUri(String file) {
    ClassPathResource resource = new ClassPathResource(root + "/" + file);
    if (!resource.exists()) {
      log.warn("branding/{} is named in brand.json but missing; no logo", file);
      return null;
    }
    try (InputStream in = resource.getInputStream()) {
      String type = IMAGE_TYPES.get(file.substring(file.lastIndexOf('.') + 1));
      return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
    } catch (IOException e) {
      log.warn("branding/{} is not readable; no logo", file, e);
      return null;
    }
  }
}
