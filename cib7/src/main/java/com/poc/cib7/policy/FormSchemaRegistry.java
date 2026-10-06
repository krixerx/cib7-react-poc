package com.poc.cib7.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads the service pack's form value schemas and checks submitted values against them for {@link
 * VariableWritePolicyFilter}.
 *
 * <p>The variable policy decides <em>which</em> variables a client may write; these schemas decide
 * <em>what values</em> they may hold (docs/security.md rule 2: client variables are untrusted).
 * Before them, rules such as "age 1 to 130" or "11-digit personal code" held only in the browser,
 * so a direct {@code /engine-rest} call could store any value.
 *
 * <p>Each file is a JSON Schema 2020-12 document that names its process and form in {@code
 * x-process} and {@code x-form} ({@code start} for the start variables). The service builder
 * generates them from the spec's Fields tables. Shared rules live in {@code schemas/core-v1.json}
 * in this jar and are referenced by id; remote references are never fetched. A file without the two
 * names, a duplicate, or a schema that does not compile stops the engine from starting, so a broken
 * schema cannot ship. A form without a schema keeps the name check only.
 */
@Component
public class FormSchemaRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(FormSchemaRegistry.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The id form schemas use to reference the shared definitions. */
  static final String CORE_ID = "https://companylab.ai/schemas/core/v1.json";

  private static final String CORE_RESOURCE = "schemas/core-v1.json";

  /** The {@code x-form} value of a process's start-variable schema. */
  public static final String START = "start";

  private record Key(String process, String form) {}

  private final Map<Key, Schema> schemas;

  @Autowired
  public FormSchemaRegistry(
      @Value("${app.form-schemas.locations:classpath*:processes/*/schemas/*.json}")
          String[] locations)
      throws IOException {
    this.schemas = load(locations);
  }

  /** The schema for a form id (without the {@code react:} prefix), if the pack has one. */
  public Optional<Schema> forForm(String processDefinitionKey, String formId) {
    return Optional.ofNullable(schemas.get(new Key(processDefinitionKey, formId)));
  }

  /** The schema for a process's start variables, if the pack has one. */
  public Optional<Schema> forStart(String processDefinitionKey) {
    return forForm(processDefinitionKey, START);
  }

  /**
   * The failed rules for a set of variable values, one per message as {@code $.field: rule}; empty
   * when they are valid.
   */
  public static List<String> violations(Schema schema, JsonNode values) {
    return schema.validate(values).stream().map(Error::toString).toList();
  }

  static Map<Key, Schema> load(String... locations) throws IOException {
    String core;
    try (InputStream in = new ClassPathResource(CORE_RESOURCE).getInputStream()) {
      core = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder.schemaLoader(
                    loader -> loader.resourceLoaders(r -> r.resources(Map.of(CORE_ID, core)))));

    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    Map<Key, Schema> loaded = new LinkedHashMap<>();
    for (String location : locations) {
      for (Resource resource : resolver.getResources(location.trim())) {
        JsonNode node;
        try (InputStream in = resource.getInputStream()) {
          node = JSON.readTree(in);
        }
        if (node == null || !node.isObject()) {
          throw new IllegalStateException("Form schema " + resource + " is not a JSON object");
        }
        String process = node.path("x-process").asText("");
        String form = node.path("x-form").asText("");
        if (process.isBlank() || form.isBlank()) {
          throw new IllegalStateException(
              "Form schema " + resource + " must name its process and form in x-process/x-form");
        }
        Key key = new Key(process, form);
        if (loaded.containsKey(key)) {
          throw new IllegalStateException(
              "Two form schemas for " + process + "/" + form + " (second: " + resource + ")");
        }
        // The two names are registry metadata, not validation keywords; the validator would warn
        // about unknown keywords.
        ObjectNode rules = node.deepCopy();
        rules.remove("x-process");
        rules.remove("x-form");
        Schema schema;
        try {
          schema =
              registry.getSchema(
                  SchemaLocation.of(
                      "https://companylab.ai/schemas/pack/" + process + "/" + form + ".json"),
                  rules);
          schema.initializeValidators();
        } catch (RuntimeException e) {
          throw new IllegalStateException("Form schema " + resource + " does not compile", e);
        }
        loaded.put(key, schema);
      }
    }
    LOG.info("Loaded {} form value schema(s)", loaded.size());
    return Map.copyOf(loaded);
  }
}
