package com.poc.backend.registry;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.pack.SpecTables;
import com.poc.backend.registry.RegistryDescriptor.Access;
import com.poc.backend.registry.RegistryDescriptor.Field;
import com.poc.backend.registry.RegistryDescriptor.Operation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Every registry serves the rows its spec seeds (docs/platform-api.md, "Service examples"): the
 * {@code ## Seed} table of {@code data/<entity>.md} is the expected data, read back through the
 * real endpoint class each operation declares, so the descriptor, its migration and the spec cannot
 * drift apart. A pack check, for any pack's registries.
 */
@Tag("pack")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.internal-task-token=" + RegistrySeedTest.TOKEN)
class RegistrySeedTest {

  static final String TOKEN = "registry-seed-test-token";

  private static final Path SPECS =
      Path.of(System.getProperty("services.docs.dir", "../packs/test/docs/business/services"));

  @Autowired MockMvc mvc;
  @Autowired RegistryCatalog catalog;
  @MockitoBean S3Client s3Client;
  @MockitoBean S3Presigner s3Presigner;
  @MockitoBean EngineClient engine;

  @Test
  void everyRegistryServesTheRowsItsSpecSeeds() throws Exception {
    List<String> problems = new ArrayList<>();
    for (Path spec : dataSpecs()) {
      String md = Files.readString(spec);
      String at = SPECS.relativize(spec).toString().replace('\\', '/');
      Optional<String> entityName = SpecTables.field(md, "Entity");
      Optional<RegistryDescriptor> entity = entityName.flatMap(catalog::entity);
      if (entity.isEmpty()) {
        problems.add(
            at + ": no registry descriptor for **Entity:** " + entityName.orElse("(none)"));
        continue;
      }
      RegistryDescriptor d = entity.get();
      Optional<SpecTables.Table> seed = SpecTables.under(md, "Seed");
      if (seed.isEmpty() || seed.get().rows().isEmpty()) {
        continue;
      }
      List<Map<String, String>> rows = seed.get().rows();
      Optional<Access> list = d.access(Operation.LIST);
      if (list.isPresent()) {
        mvc.perform(request(list.get(), d.entity(), null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(rows.size())));
      }
      Optional<Access> lookup = d.access(Operation.LOOKUP);
      if (lookup.isEmpty()) {
        continue;
      }
      for (Map<String, String> row : rows) {
        String key = SpecTables.unquote(row.get(d.key()));
        var result = mvc.perform(request(lookup.get(), d.entity(), key));
        result.andExpect(status().isOk());
        for (Field field : d.fields()) {
          String cell = row.get(field.name());
          if (cell == null) {
            problems.add(at + ": the seed has no column " + field.name());
            continue;
          }
          result.andExpect(jsonPath("$['" + field.name() + "']").value(typed(field, cell)));
        }
      }
    }
    assertEquals(List.of(), problems);
  }

  private static MockHttpServletRequestBuilder request(Access access, String entity, String key) {
    String path =
        "/api/"
            + (access == Access.PUBLIC ? "public" : "internal")
            + "/registry/"
            + entity
            + (key == null ? "" : "/" + URLEncoder.encode(key, StandardCharsets.UTF_8));
    MockHttpServletRequestBuilder request = get(path);
    return access == Access.INTERNAL ? request.header("X-Internal-Token", TOKEN) : request;
  }

  /** A seed cell as the API returns the field: the descriptor's type decides. */
  private static Object typed(Field field, String cell) {
    String v = SpecTables.unquote(cell);
    return switch (field.type()) {
      case INTEGER -> Integer.valueOf(v);
      case NUMBER -> Double.valueOf(v);
      case BOOLEAN -> Boolean.valueOf(v);
      case STRING -> v;
    };
  }

  private static List<Path> dataSpecs() throws Exception {
    if (!Files.isDirectory(SPECS)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(SPECS)) {
      List<Path> specs =
          walk.filter(
                  p ->
                      p.getParent() != null
                          && p.getParent().getFileName().toString().equals("data")
                          && p.toString().endsWith(".md"))
              .sorted()
              .toList();
      assertTrue(specs.stream().allMatch(Files::isRegularFile));
      return specs;
    }
  }
}
