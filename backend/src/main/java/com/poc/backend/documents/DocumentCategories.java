package com.poc.backend.documents;

import com.poc.backend.pack.PackManifest;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The document categories a case may hold, and who may create a document of each: the applicant (an
 * upload) or the system (a PDF the engine renders and files through the internal endpoints). The
 * service pack declares its own in {@code documents.json}; the core adds {@link #CERTIFICATE}, the
 * issued certificate the mobile wallet and the approval signal are built around.
 *
 * <p>Who creates a category is the point: a user-facing endpoint accepts only applicant categories,
 * so nobody can file their own PDF as a {@code generated-certificate} and have it shown as issued
 * (docs/security.md, external facts need proof). System categories start with {@code generated-}
 * and applicant ones do not, because the SPA and the mobile app tell them apart by that prefix.
 */
@Component
public class DocumentCategories {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentCategories.class);

  /** The core's own category: the certificate a case issues. */
  public static final String CERTIFICATE = "generated-certificate";

  static final String SYSTEM_PREFIX = "generated-";
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final Set<String> ROOT_KEYS = Set.of("$comment", "platform", "categories");

  /** Who creates documents of a category. */
  public enum By {
    APPLICANT,
    SYSTEM
  }

  private final Map<String, By> categories;

  public DocumentCategories(
      @Value("${app.documents.categories:classpath*:documents.json}") String location)
      throws IOException {
    this.categories = load(location);
    LOG.info("Document categories: {}", categories);
  }

  /** Whether an applicant may upload a document of this category. */
  public boolean isApplicantUpload(String category) {
    return categories.get(category) == By.APPLICANT;
  }

  /** Whether the engine may file a rendered document under this category. */
  public boolean isSystem(String category) {
    return categories.get(category) == By.SYSTEM;
  }

  /** All categories with their creator. */
  public Map<String, By> all() {
    return categories;
  }

  static Map<String, By> load(String location) throws IOException {
    Map<String, By> loaded = new LinkedHashMap<>();
    loaded.put(CERTIFICATE, By.SYSTEM);
    Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
    if (resources.length > 1) {
      throw new IllegalStateException("More than one documents.json on the classpath");
    }
    for (Resource resource : resources) {
      try (InputStream in = resource.getInputStream()) {
        parse(new Yaml(new SafeConstructor(new LoaderOptions())).load(in)).forEach(loaded::put);
      } catch (RuntimeException e) {
        throw new IllegalStateException(resource + " is invalid: " + e.getMessage(), e);
      }
    }
    return Map.copyOf(loaded);
  }

  /**
   * Checks a parsed {@code documents.json} (JSON, read with the YAML parser like every descriptor).
   */
  static Map<String, By> parse(Object document) {
    if (!(document instanceof Map<?, ?> root)) {
      throw new IllegalArgumentException("documents.json must be an object");
    }
    for (Object key : root.keySet()) {
      if (!ROOT_KEYS.contains(String.valueOf(key))) {
        throw new IllegalArgumentException("unknown key '" + key + "'");
      }
    }
    if (!(root.get("platform") instanceof Integer p) || p != PackManifest.PLATFORM_MAJOR) {
      throw new IllegalArgumentException("platform must be " + PackManifest.PLATFORM_MAJOR);
    }
    if (!(root.get("categories") instanceof Map<?, ?> categories)) {
      throw new IllegalArgumentException("categories must be an object");
    }
    Map<String, By> parsed = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : categories.entrySet()) {
      String name = String.valueOf(entry.getKey());
      if (!NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("category '" + name + "' is not lowercase-kebab");
      }
      if (CERTIFICATE.equals(name)) {
        throw new IllegalArgumentException(CERTIFICATE + " is the core's own category");
      }
      if (!(entry.getValue() instanceof Map<?, ?> spec)
          || spec.size() != 1
          || !(spec.get("by") instanceof String byText)) {
        throw new IllegalArgumentException(
            name + " must be { \"by\": \"applicant\" | \"system\" }");
      }
      By by =
          switch (byText) {
            case "applicant" -> By.APPLICANT;
            case "system" -> By.SYSTEM;
            default -> throw new IllegalArgumentException(name + ".by must be applicant or system");
          };
      if ((by == By.SYSTEM) != name.startsWith(SYSTEM_PREFIX)) {
        throw new IllegalArgumentException(
            name + ": system categories start with '" + SYSTEM_PREFIX + "', applicant ones do not");
      }
      parsed.put(name, by);
    }
    return parsed;
  }
}
