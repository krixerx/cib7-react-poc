package com.poc.backend.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The pack manifest and the platform API compatibility rule (docs/platform-api.md). */
class PackManifestTest {

  private static Object yaml(String text) {
    return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
  }

  private static PackManifest manifest(String platform) {
    return PackManifest.parse(
        yaml("name: acme\nversion: \"1.2.3\"\nplatform: \"" + platform + "\"\n"));
  }

  @Tag("pack")
  @Test
  void thePackOnTheClasspathRunsOnThisCore() {
    PackManifest pack = PackManifest.load().requireCompatible();
    assertEquals(PackManifest.PLATFORM_MAJOR, pack.platformMajor());
  }

  @Test
  void anOlderMinorOfTheSameMajorRuns() {
    manifest(PackManifest.PLATFORM_MAJOR + ".0").requireCompatible();
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.0", "3.0", "2.99"})
  void anotherMajorOrANewerMinorIsRefused(String platform) {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> manifest(platform).requireCompatible());
    assertTrue(e.getMessage().contains("needs platform API " + platform), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "name: acme\nversion: \"1.2.3\"\nplatform: 2.0\n",
        "name: acme\nversion: \"1.2.3\"\nplatform: \"2\"\n",
        "name: Acme Pack\nversion: \"1.2.3\"\nplatform: \"2.0\"\n",
        "name: acme\nversion: \"1.2\"\nplatform: \"2.0\"\n",
        "name: acme\nversion: \"1.2.3\"\nplatform: \"2.0\"\nscripts: x\n",
        "- not a mapping\n",
      })
  void aMalformedManifestIsRefused(String text) {
    assertThrows(IllegalArgumentException.class, () -> PackManifest.parse(yaml(text)));
  }

  /** The version this core provides is the one the contract states. */
  @Test
  void theContractStatesThisPlatformVersion() throws Exception {
    String doc =
        Files.readString(Path.of(System.getProperty("platform.doc", "../docs/platform-api.md")));
    Matcher m = Pattern.compile("\\*\\*Platform API version:\\*\\* `(\\d+)\\.(\\d+)`").matcher(doc);
    assertTrue(m.find(), "docs/platform-api.md states no platform API version");
    assertEquals(PackManifest.PLATFORM_MAJOR, Integer.parseInt(m.group(1)));
    assertEquals(PackManifest.PLATFORM_MINOR, Integer.parseInt(m.group(2)));
  }
}
