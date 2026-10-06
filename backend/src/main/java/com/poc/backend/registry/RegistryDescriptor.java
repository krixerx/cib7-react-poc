package com.poc.backend.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One registry entity as a service pack declares it in {@code registry/<entity>.yaml}, already
 * checked by {@link RegistryCatalog}. Every name in it is safe to put into SQL as a quoted
 * identifier and into a URL path, because the catalog accepts only a closed set of shapes.
 *
 * @param entity the path segment, {@code /api/<class>/registry/<entity>}
 * @param table the table in the {@code registry} schema
 * @param key the field a lookup matches
 * @param sort the field a list is ordered by (then by key)
 * @param fields stored fields in declaration order
 * @param derived fields computed when read, in declaration order
 * @param operations operation to the endpoint class that serves it
 */
public record RegistryDescriptor(
    String entity,
    String table,
    String key,
    String sort,
    List<Field> fields,
    List<Derived> derived,
    Map<Operation, Access> operations) {

  /** A stored field: its JSON name, its column and its type. */
  public record Field(String name, String column, Type type) {}

  /** A field computed from another one when read. Only {@code yearsSince} exists so far. */
  public record Derived(String name, String yearsSinceField) {}

  /** Value types a field may have. */
  public enum Type {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN
  }

  /** What a client may do with the entity. */
  public enum Operation {
    LIST,
    LOOKUP
  }

  /**
   * The endpoint class that serves an operation (docs/security.md rule 5). The closed set is the
   * point: a pack can choose a class, never write an endpoint.
   */
  public enum Access {
    /** {@code /api/public/**}: unauthenticated, so harmless reference data only. */
    PUBLIC,
    /** {@code /api/internal/**}: the engine through the bus, with {@code X-Internal-Token}. */
    INTERNAL
  }

  /** The class serving an operation, if the entity offers it at all. */
  public Optional<Access> access(Operation operation) {
    return Optional.ofNullable(operations.get(operation));
  }

  /** The stored field with this name. */
  public Field field(String name) {
    return fields.stream()
        .filter(f -> f.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(entity + " has no field " + name));
  }
}
