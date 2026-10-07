package com.poc.cib7.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The realm is two files Keycloak imports from one directory: the core realm ({@code
 * keycloak/cib7-poc-realm.json}: settings, roles, groups, clients, their service accounts) and the
 * service pack's users ({@code <pack>/keycloak/cib7-poc-users-0.json}). Pack users may only join
 * the core groups: those are the only ones {@code AuthorizationBootstrap} gives engine grants, so a
 * pack group would let nobody reach a task. The pull-only deploy bundle carries copies of the realm
 * files, the theme and the pack's branding, which must stay identical to their sources.
 */
class RealmFilesTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path CORE_REALM = Path.of("../keycloak/cib7-poc-realm.json");
  private static final Path PACK_USERS =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"))
          .resolve("../keycloak/cib7-poc-users-0.json")
          .normalize();
  private static final Set<String> CORE_GROUPS = Set.of("applicant", "civil-servant", "cib7-admin");
  private static final Set<String> USER_KEYS =
      Set.of(
          "username",
          "enabled",
          "emailVerified",
          "firstName",
          "lastName",
          "email",
          "credentials",
          "groups");
  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");
  private static final String SERVICE_ACCOUNT = "service-account-";

  private static JsonNode read(Path path) throws IOException {
    return JSON.readTree(path.toFile());
  }

  @Test
  void theCoreRealmHoldsOnlyCoreGroupsAndServiceAccounts() throws IOException {
    JsonNode realm = read(CORE_REALM);
    Set<String> groups = new TreeSet<>();
    realm.path("groups").forEach(g -> groups.add(g.path("name").asText()));
    assertEquals(new TreeSet<>(CORE_GROUPS), groups);
    realm
        .path("users")
        .forEach(
            u ->
                assertTrue(
                    u.path("username").asText().startsWith(SERVICE_ACCOUNT),
                    u.path("username").asText() + " belongs in the pack's users file"));
  }

  @Tag("pack")
  @Test
  void thePacksUsersJoinOnlyCoreGroups() throws IOException {
    // A pack need not seed users; a customer may create them in Keycloak instead.
    assumeTrue(java.nio.file.Files.exists(PACK_USERS), "the pack seeds no users");
    JsonNode file = read(PACK_USERS);
    assertEquals(read(CORE_REALM).path("realm").asText(), file.path("realm").asText());
    Set<String> fileKeys = new HashSet<>();
    file.fieldNames().forEachRemaining(fileKeys::add);
    assertEquals(Set.of("realm", "users"), fileKeys, "the users file holds a realm name and users");
    Set<String> seen = new HashSet<>();
    for (JsonNode user : file.path("users")) {
      String name = user.path("username").asText();
      assertTrue(USERNAME.matcher(name).matches(), "username " + name);
      assertTrue(!name.startsWith(SERVICE_ACCOUNT), name + ": service accounts are core");
      assertTrue(seen.add(name), name + " twice");
      user.fieldNames()
          .forEachRemaining(
              key -> assertTrue(USER_KEYS.contains(key), name + ": '" + key + "' is not allowed"));
      for (JsonNode group : user.path("groups")) {
        String path = group.asText();
        assertTrue(
            path.startsWith("/") && CORE_GROUPS.contains(path.substring(1)),
            name + " joins " + path + ", which is not a core group");
      }
      for (JsonNode credential : user.path("credentials")) {
        assertEquals("password", credential.path("type").asText(), name + ": credential type");
      }
    }
  }

  /** The pull-only deploy bundle carries copies; a stale copy silently ships an old realm. */
  @Test
  void theDeployBundleCopiesMatchTheirSources() throws IOException {
    assertEquals(
        read(CORE_REALM), read(Path.of("../deploy/keycloak/cib7-poc-realm.json")), "realm copy");
    assertEquals(
        read(Path.of("../packs/reference/keycloak/cib7-poc-users-0.json")),
        read(Path.of("../deploy/keycloak/cib7-poc-users-0.json")),
        "users copy");
    assertSameTree(Path.of("../keycloak/themes"), Path.of("../deploy/keycloak/themes"));
    assertSameTree(Path.of("../packs/reference/branding"), Path.of("../deploy/branding"));
  }

  /** Same files with the same content; line endings may differ (Git's autocrlf). */
  private static void assertSameTree(Path source, Path copy) throws IOException {
    java.util.Map<String, String> a = tree(source);
    java.util.Map<String, String> b = tree(copy);
    assertEquals(a.keySet(), b.keySet(), copy + " lists other files than " + source);
    a.forEach((name, content) -> assertEquals(content, b.get(name), copy + "/" + name));
  }

  private static java.util.Map<String, String> tree(Path root) throws IOException {
    java.util.Map<String, String> files = new java.util.TreeMap<>();
    try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root)) {
      for (Path file : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
        String name = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        files.put(name, text.replace("\r\n", "\n"));
      }
    }
    return files;
  }
}
