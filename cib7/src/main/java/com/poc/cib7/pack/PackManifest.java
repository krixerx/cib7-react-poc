package com.poc.cib7.pack;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The service pack's manifest, {@code pack.yaml}: the pack's name and version and the platform API
 * version it was built for. The core refuses at startup a pack that needs another platform API
 * major or a newer minor than this core provides (docs/platform-api.md), so a mismatch shows up as
 * one clear startup error, not as half-working services.
 *
 * <p>The backend holds the same class ({@code com.poc.backend.pack.PackManifest}); there is no
 * shared Java module, and both must agree with {@link #PLATFORM_MAJOR} / {@link #PLATFORM_MINOR}.
 */
public record PackManifest(String name, String version, int platformMajor, int platformMinor) {

  /** The platform API this core provides. Keep in step with docs/platform-api.md. */
  public static final int PLATFORM_MAJOR = 2;

  public static final int PLATFORM_MINOR = 0;

  /**
   * Core classes a pack's BPMN may name (a {@code camunda:class} or a listener {@code class}).
   * Everything else a pack does is data; adding a class here is a platform API minor.
   */
  public static final Set<String> RELEASED_CLASSES =
      Set.of(
          // Builds the co-signing parties on the task that collects them (docs/security.md rule 3).
          "com.poc.cib7.consent.ConsentPartiesListener");

  /** Where the manifest sits on the classpath: the pack root, next to engine/ or backend/. */
  public static final String RESOURCE = "pack.yaml";

  private static final Set<String> KEYS = Set.of("name", "version", "platform");
  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
  private static final Pattern VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
  private static final Pattern PLATFORM = Pattern.compile("^(\\d+)\\.(\\d+)$");

  /** Reads {@code pack.yaml} from the classpath; a pack without one is not a pack. */
  public static PackManifest load() {
    ClassPathResource resource = new ClassPathResource(RESOURCE);
    if (!resource.exists()) {
      throw new IllegalStateException(
          "No pack.yaml on the classpath: the service pack is missing or incomplete"
              + " (docs/platform-api.md).");
    }
    try (InputStream in = resource.getInputStream()) {
      return parse(new Yaml(new SafeConstructor(new LoaderOptions())).load(in));
    } catch (IOException e) {
      throw new IllegalStateException("pack.yaml is not readable", e);
    }
  }

  /** Checks the manifest's shape; strict like every pack format. */
  public static PackManifest parse(Object raw) {
    if (!(raw instanceof Map<?, ?> root)) {
      throw new IllegalArgumentException("pack.yaml must be a mapping");
    }
    for (Object key : root.keySet()) {
      if (!KEYS.contains(String.valueOf(key))) {
        throw new IllegalArgumentException("pack.yaml has an unknown key '" + key + "'");
      }
    }
    String name = text(root, "name", NAME);
    String version = text(root, "version", VERSION);
    Matcher platform = PLATFORM.matcher(text(root, "platform", PLATFORM));
    platform.matches();
    return new PackManifest(
        name, version, Integer.parseInt(platform.group(1)), Integer.parseInt(platform.group(2)));
  }

  private static String text(Map<?, ?> root, String key, Pattern pattern) {
    Object value = root.get(key);
    if (!(value instanceof String s) || !pattern.matcher(s).matches()) {
      throw new IllegalArgumentException(
          "pack.yaml " + key + " must match " + pattern + " (a quoted string), was " + value);
    }
    return s;
  }

  /**
   * Fails unless this core can run the pack: same platform API major, and a minor at least the one
   * the pack needs (minors only add).
   */
  public PackManifest requireCompatible() {
    if (platformMajor != PLATFORM_MAJOR || platformMinor > PLATFORM_MINOR) {
      throw new IllegalStateException(
          String.format(
              "Service pack '%s' %s needs platform API %d.%d; this core provides %d.%d."
                  + " Use a core release with platform API %d.x (at least %d.%d).",
              name,
              version,
              platformMajor,
              platformMinor,
              PLATFORM_MAJOR,
              PLATFORM_MINOR,
              platformMajor,
              platformMajor,
              platformMinor));
    }
    return this;
  }

  @Override
  public String toString() {
    return name + " " + version + " (platform API " + platformMajor + "." + platformMinor + ")";
  }
}
