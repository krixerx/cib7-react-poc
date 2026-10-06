package com.poc.backend.registry;

import com.poc.backend.pack.PackManifest;
import com.poc.backend.registry.RegistryDescriptor.Access;
import com.poc.backend.registry.RegistryDescriptor.Derived;
import com.poc.backend.registry.RegistryDescriptor.Field;
import com.poc.backend.registry.RegistryDescriptor.Operation;
import com.poc.backend.registry.RegistryDescriptor.Type;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
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
 * Loads the service pack's registry descriptors and refuses to start on anything outside the closed
 * set the platform supports.
 *
 * <p>Registry entities used to be hand-written Java per service (a controller, an entity, a
 * repository, seed code), and every new one was a new chance to get docs/security.md wrong. A pack
 * now declares fields, a key, derived fields and which operation is served from which endpoint
 * class; {@link RegistryController} serves all of them the same way. Names become quoted SQL
 * identifiers and URL segments, so they must match strict patterns; types, operations, endpoint
 * classes and derived-field rules come from enums. A descriptor whose table or columns are missing
 * from the database also stops startup, so a descriptor and its migration cannot drift apart.
 */
@Component
public class RegistryCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(RegistryCatalog.class);

  /** The descriptor format this backend understands (platform API major). */
  static final int PLATFORM = PackManifest.PLATFORM_MAJOR;

  private static final Pattern ENTITY = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final Pattern TABLE = Pattern.compile("reg_[a-z0-9_]{1,59}");
  private static final Pattern FIELD = Pattern.compile("[a-z][a-zA-Z0-9]{0,62}");

  private final Map<String, RegistryDescriptor> entities;

  public RegistryCatalog(
      RegistryMigrations migrations,
      DataSource dataSource,
      @Value("${app.registry.locations:classpath*:registry/*.yaml}") String[] locations)
      throws IOException, SQLException {
    this.entities = load(locations);
    try (Connection connection = dataSource.getConnection()) {
      for (RegistryDescriptor descriptor : entities.values()) {
        checkColumns(connection, migrations.schema(), descriptor);
      }
    }
    LOG.info("Registry entities: {}", entities.keySet());
  }

  /** The descriptor for an entity path segment, if the pack declares one. */
  public Optional<RegistryDescriptor> entity(String entity) {
    return Optional.ofNullable(entities.get(entity));
  }

  static Map<String, RegistryDescriptor> load(String... locations) throws IOException {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, RegistryDescriptor> loaded = new LinkedHashMap<>();
    for (String location : locations) {
      for (Resource resource : resolver.getResources(location.trim())) {
        Object document;
        try (InputStream in = resource.getInputStream()) {
          document = yaml.load(in);
        }
        RegistryDescriptor descriptor;
        try {
          descriptor = parse(document);
        } catch (RuntimeException e) {
          throw new IllegalStateException(
              "Registry descriptor " + resource + " is invalid: " + e.getMessage(), e);
        }
        if (loaded.putIfAbsent(descriptor.entity(), descriptor) != null) {
          throw new IllegalStateException(
              "Two registry descriptors for '"
                  + descriptor.entity()
                  + "' (second: "
                  + resource
                  + ")");
        }
      }
    }
    return Map.copyOf(loaded);
  }

  static RegistryDescriptor parse(Object document) {
    Map<String, Object> root = map(document, "the document");
    Object platform = root.get("platform");
    if (!(platform instanceof Integer p) || p != PLATFORM) {
      throw new IllegalArgumentException("platform must be " + PLATFORM + ", was " + platform);
    }
    String entity = name(root, "entity", ENTITY);
    String table = name(root, "table", TABLE);

    List<Field> fields = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (Map.Entry<String, Object> e : map(root.get("fields"), "fields").entrySet()) {
      String fieldName = checked(e.getKey(), FIELD, "field");
      Map<String, Object> spec = map(e.getValue(), "field " + fieldName);
      Type type = enumValue(Type.class, spec.get("type"), "type of " + fieldName);
      fields.add(new Field(fieldName, column(fieldName), type));
      names.add(fieldName);
    }
    if (fields.isEmpty()) {
      throw new IllegalArgumentException("fields must not be empty");
    }
    String key = checked(String.valueOf(root.get("key")), FIELD, "key");
    if (!names.contains(key)) {
      throw new IllegalArgumentException("key '" + key + "' is not a field");
    }
    String sort = root.containsKey("sort") ? String.valueOf(root.get("sort")) : key;
    if (!names.contains(sort)) {
      throw new IllegalArgumentException("sort '" + sort + "' is not a field");
    }

    List<Derived> derived = new ArrayList<>();
    Object derivedNode = root.get("derived");
    if (derivedNode != null) {
      for (Map.Entry<String, Object> e : map(derivedNode, "derived").entrySet()) {
        String name = checked(e.getKey(), FIELD, "derived field");
        if (!names.add(name)) {
          throw new IllegalArgumentException("derived field '" + name + "' repeats a name");
        }
        Map<String, Object> rule = map(e.getValue(), "derived " + name);
        if (rule.size() != 1 || !rule.containsKey("yearsSince")) {
          throw new IllegalArgumentException(
              "derived " + name + " must be exactly {yearsSince: <integer field>}");
        }
        String source = String.valueOf(rule.get("yearsSince"));
        boolean integerField =
            fields.stream().anyMatch(f -> f.name().equals(source) && f.type() == Type.INTEGER);
        if (!integerField) {
          throw new IllegalArgumentException(
              "derived " + name + ": yearsSince needs an integer field, got '" + source + "'");
        }
        derived.add(new Derived(name, source));
      }
    }

    Map<Operation, Access> operations = new EnumMap<>(Operation.class);
    for (Map.Entry<String, Object> e : map(root.get("operations"), "operations").entrySet()) {
      Operation operation = enumValue(Operation.class, e.getKey(), "operation");
      Map<String, Object> spec = map(e.getValue(), "operation " + e.getKey());
      operations.put(
          operation, enumValue(Access.class, spec.get("access"), "access of " + e.getKey()));
    }
    if (operations.isEmpty()) {
      throw new IllegalArgumentException("operations must not be empty");
    }
    return new RegistryDescriptor(
        entity,
        table,
        key,
        sort,
        List.copyOf(fields),
        List.copyOf(derived),
        Map.copyOf(operations));
  }

  /** camelCase field name to its snake_case column. */
  static String column(String field) {
    return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  private static void checkColumns(
      Connection connection, String schema, RegistryDescriptor descriptor) throws SQLException {
    Set<String> present = new HashSet<>();
    try (ResultSet rs =
        connection.getMetaData().getColumns(null, schema, descriptor.table(), null)) {
      while (rs.next()) {
        present.add(rs.getString("COLUMN_NAME"));
      }
    }
    for (Field field : descriptor.fields()) {
      if (!present.contains(field.column())) {
        throw new IllegalStateException(
            "Registry '"
                + descriptor.entity()
                + "': column "
                + schema
                + "."
                + descriptor.table()
                + "."
                + field.column()
                + " does not exist; the pack's db/registry migrations and its descriptor"
                + " disagree");
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object node, String what) {
    if (!(node instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException(what + " must be a mapping");
    }
    return (Map<String, Object>) m;
  }

  private static String name(Map<String, Object> root, String key, Pattern pattern) {
    return checked(String.valueOf(root.get(key)), pattern, key);
  }

  private static String checked(String value, Pattern pattern, String what) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(what + " '" + value + "' must match " + pattern);
    }
    return value;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String what) {
    try {
      return Enum.valueOf(type, String.valueOf(value).toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(what + " '" + value + "' is not supported");
    }
  }
}
