package com.poc.backend.registry;

import com.poc.backend.registry.RegistryDescriptor.Access;
import com.poc.backend.registry.RegistryDescriptor.Derived;
import com.poc.backend.registry.RegistryDescriptor.Field;
import com.poc.backend.registry.RegistryDescriptor.Operation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves every registry entity a service pack declares, under the endpoint class its descriptor
 * names (docs/security.md rule 5): {@code /api/public/registry/<entity>[/<key>]} for harmless
 * reference data, {@code /api/internal/registry/<entity>[/<key>]} for the engine through the bus.
 *
 * <p>An entity, or an operation of it, that the descriptor does not offer on the requested class is
 * a 404, the same answer as an unknown entity, so the public path reveals nothing about internal
 * registries. Identifiers in the SQL come from the checked descriptor and are always quoted; the
 * key is always a bound parameter. A list returns at most {@link #LIST_LIMIT} rows.
 */
@RestController
public class RegistryController {

  static final int LIST_LIMIT = 1000;

  private final RegistryCatalog catalog;
  private final RegistryMigrations migrations;
  private final NamedParameterJdbcTemplate jdbc;

  public RegistryController(
      RegistryCatalog catalog, RegistryMigrations migrations, NamedParameterJdbcTemplate jdbc) {
    this.catalog = catalog;
    this.migrations = migrations;
    this.jdbc = jdbc;
  }

  @GetMapping("/api/public/registry/{entity}")
  public List<Map<String, Object>> publicList(@PathVariable String entity) {
    return list(served(entity, Operation.LIST, Access.PUBLIC));
  }

  @GetMapping("/api/public/registry/{entity}/{key}")
  public Map<String, Object> publicLookup(@PathVariable String entity, @PathVariable String key) {
    return lookup(served(entity, Operation.LOOKUP, Access.PUBLIC), key);
  }

  @GetMapping("/api/internal/registry/{entity}")
  public List<Map<String, Object>> internalList(@PathVariable String entity) {
    return list(served(entity, Operation.LIST, Access.INTERNAL));
  }

  @GetMapping("/api/internal/registry/{entity}/{key}")
  public Map<String, Object> internalLookup(@PathVariable String entity, @PathVariable String key) {
    return lookup(served(entity, Operation.LOOKUP, Access.INTERNAL), key);
  }

  private RegistryDescriptor served(String entity, Operation operation, Access access) {
    return catalog
        .entity(entity)
        .filter(d -> d.access(operation).filter(access::equals).isPresent())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private List<Map<String, Object>> list(RegistryDescriptor d) {
    String sql =
        select(d)
            + " order by "
            + quote(d.field(d.sort()).column())
            + ", "
            + quote(d.field(d.key()).column())
            + " limit "
            + LIST_LIMIT;
    return jdbc.query(sql, Map.of(), (rs, row) -> toJson(d, rs));
  }

  private Map<String, Object> lookup(RegistryDescriptor d, String key) {
    Object keyValue = keyValue(d, key);
    String sql = select(d) + " where " + quote(d.field(d.key()).column()) + " = :key";
    List<Map<String, Object>> rows =
        jdbc.query(sql, new MapSqlParameterSource("key", keyValue), (rs, row) -> toJson(d, rs));
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return rows.get(0);
  }

  private String select(RegistryDescriptor d) {
    String columns =
        d.fields().stream().map(f -> quote(f.column())).collect(Collectors.joining(", "));
    return "select " + columns + " from " + quote(migrations.schema()) + "." + quote(d.table());
  }

  /** The key converted to its field's type; a key that cannot be one matches no row. */
  private static Object keyValue(RegistryDescriptor d, String key) {
    Field field = d.field(d.key());
    try {
      return switch (field.type()) {
        case STRING -> key;
        case INTEGER -> Integer.valueOf(key);
        case NUMBER -> Double.valueOf(key);
        case BOOLEAN -> Boolean.valueOf(key);
      };
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static Map<String, Object> toJson(RegistryDescriptor d, ResultSet rs)
      throws SQLException {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Field field : d.fields()) {
      Object value =
          switch (field.type()) {
            case STRING -> rs.getString(field.column());
            case INTEGER -> rs.getInt(field.column());
            case NUMBER -> rs.getDouble(field.column());
            case BOOLEAN -> rs.getBoolean(field.column());
          };
      out.put(field.name(), rs.wasNull() ? null : value);
    }
    int currentYear = Year.now().getValue();
    for (Derived derived : d.derived()) {
      Object year = out.get(derived.yearsSinceField());
      out.put(derived.name(), year instanceof Integer y ? Math.max(0, currentYear - y) : null);
    }
    return out;
  }

  private static String quote(String identifier) {
    return "\"" + identifier + "\"";
  }
}
