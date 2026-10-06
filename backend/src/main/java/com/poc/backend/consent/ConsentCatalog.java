package com.poc.backend.consent;

import com.poc.backend.consent.ConsentFlow.Config;
import com.poc.backend.consent.ConsentFlow.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * Loads the service pack's co-signing descriptors ({@code consent/<purpose>.yaml}) and refuses to
 * start on anything outside the closed set.
 *
 * <p>Co-owner confirmation and co-founder signature used to be one hand-written controller each
 * around {@link ConsentFlow}, differing only in variable names, wording and what the signing party
 * is shown. A pack now declares those, and {@link ConsentController} serves every purpose the same
 * way. What a co-signer may see about the case is limited to detail types that cannot leak more
 * than named: {@code names} reduces a list of people to display names, so personal codes never
 * reach the unauthenticated page.
 */
@Component
public class ConsentCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(ConsentCatalog.class);

  static final int PLATFORM = 2;

  private static final Pattern PURPOSE = Pattern.compile("[a-z][a-z0-9-]{0,30}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");
  private static final Pattern CATEGORY = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final int MAX_TEXT = 300;

  /** One co-signing purpose as the pack declares it. */
  public record Descriptor(
      String purpose,
      Config config,
      Messages messages,
      Map<String, Detail> details,
      Map<String, DocumentRef> documents) {}

  /** A case variable the signing party is shown, reduced to its declared type. */
  public record Detail(String variable, DetailType type) {}

  /** What a detail may be: a text, a number, or a list of people reduced to their names. */
  public enum DetailType {
    STRING,
    NUMBER,
    NAMES
  }

  /** A case document the signing party may download: its id variable and its upload category. */
  public record DocumentRef(String variable, String category) {}

  private final Map<String, Descriptor> purposes;

  public ConsentCatalog(
      @Value("${app.consent.locations:classpath*:consent/*.yaml}") String[] locations)
      throws IOException {
    this.purposes = load(locations);
    LOG.info("Co-signing purposes: {}", purposes.keySet());
  }

  /** The descriptor for a purpose path segment, if the pack declares one. */
  public Optional<Descriptor> purpose(String purpose) {
    return Optional.ofNullable(purposes.get(purpose));
  }

  static Map<String, Descriptor> load(String... locations) throws IOException {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, Descriptor> loaded = new LinkedHashMap<>();
    for (String location : locations) {
      for (Resource resource : resolver.getResources(location.trim())) {
        Object document;
        try (InputStream in = resource.getInputStream()) {
          document = yaml.load(in);
        }
        Descriptor descriptor;
        try {
          descriptor = parse(document);
        } catch (RuntimeException e) {
          throw new IllegalStateException(
              "Consent descriptor " + resource + " is invalid: " + e.getMessage(), e);
        }
        if (loaded.putIfAbsent(descriptor.purpose(), descriptor) != null) {
          throw new IllegalStateException(
              "Two consent descriptors for '"
                  + descriptor.purpose()
                  + "' (second: "
                  + resource
                  + ")");
        }
      }
    }
    return Map.copyOf(loaded);
  }

  static Descriptor parse(Object document) {
    Map<String, Object> root = map(document, "the document");
    Object platform = root.get("platform");
    if (!(platform instanceof Integer p) || p != PLATFORM) {
      throw new IllegalArgumentException("platform must be " + PLATFORM + ", was " + platform);
    }
    String purpose = checked(root.get("purpose"), PURPOSE, "purpose");
    String process = checked(root.get("process"), NAME, "process");
    Map<String, Object> applicant = map(root.get("applicant"), "applicant");
    Map<String, Object> variables = map(root.get("variables"), "variables");
    Map<String, Object> messages = map(root.get("messages"), "messages");
    Map<String, Object> wording = map(root.get("wording"), "wording");

    Config config =
        new Config(
            process,
            purpose,
            checked(applicant.get("firstName"), NAME, "applicant.firstName"),
            checked(applicant.get("lastName"), NAME, "applicant.lastName"),
            checked(variables.get("parties"), NAME, "variables.parties"),
            checked(variables.get("confirmations"), NAME, "variables.confirmations"),
            checked(variables.get("rejected"), NAME, "variables.rejected"),
            checked(variables.get("sent"), NAME, "variables.sent"),
            checked(messages.get("signature"), NAME, "messages.signature"),
            checked(messages.get("send"), NAME, "messages.send"),
            text(wording, "party"),
            text(wording, "rejection"));
    Messages texts =
        new Messages(
            text(wording, "unknownLink"),
            text(wording, "alreadyRejected"),
            text(wording, "alreadySent"),
            text(wording, "alreadySigned"),
            text(wording, "notWaiting"),
            text(wording, "rejectedBack"),
            text(wording, "notReady"),
            text(wording, "notWaitingForSend"));

    Map<String, Detail> details = new LinkedHashMap<>();
    Object detailsNode = root.get("details");
    if (detailsNode != null) {
      for (Map.Entry<String, Object> e : map(detailsNode, "details").entrySet()) {
        String name = checked(e.getKey(), NAME, "detail");
        Map<String, Object> spec = map(e.getValue(), "detail " + name);
        details.put(
            name,
            new Detail(
                checked(spec.get("variable"), NAME, "variable of " + name),
                enumValue(spec.get("type"), "type of " + name)));
      }
    }
    Map<String, DocumentRef> documents = new LinkedHashMap<>();
    Object documentsNode = root.get("documents");
    if (documentsNode != null) {
      for (Map.Entry<String, Object> e : map(documentsNode, "documents").entrySet()) {
        String name = checked(e.getKey(), PURPOSE, "document");
        Map<String, Object> spec = map(e.getValue(), "document " + name);
        documents.put(
            name,
            new DocumentRef(
                checked(spec.get("variable"), NAME, "variable of " + name),
                checked(spec.get("category"), CATEGORY, "category of " + name)));
      }
    }
    return new Descriptor(
        purpose,
        config,
        texts,
        java.util.Collections.unmodifiableMap(details),
        java.util.Collections.unmodifiableMap(documents));
  }

  private static String text(Map<String, Object> wording, String key) {
    Object value = wording.get(key);
    if (!(value instanceof String s) || s.isBlank() || s.length() > MAX_TEXT) {
      throw new IllegalArgumentException(
          "wording." + key + " must be a text of 1 to " + MAX_TEXT + " characters");
    }
    return s;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object node, String what) {
    if (!(node instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException(what + " must be a mapping");
    }
    return (Map<String, Object>) m;
  }

  private static String checked(Object value, Pattern pattern, String what) {
    String text = value == null ? null : String.valueOf(value);
    if (text == null || !pattern.matcher(text).matches()) {
      throw new IllegalArgumentException(what + " '" + text + "' must match " + pattern);
    }
    return text;
  }

  private static DetailType enumValue(Object value, String what) {
    List<String> allowed = new ArrayList<>();
    for (DetailType t : DetailType.values()) {
      allowed.add(t.name().toLowerCase(Locale.ROOT));
    }
    try {
      return DetailType.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(what + " '" + value + "' is not one of " + allowed);
    }
  }
}
