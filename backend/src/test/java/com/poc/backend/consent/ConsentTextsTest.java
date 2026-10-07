package com.poc.backend.consent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poc.backend.consent.ConsentCatalog.Descriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Every co-signing purpose the pack declares has its wording on the SPA's one consent page ({@code
 * /consent/<purpose>/<token>}): the page's texts per purpose, and a label for each detail and
 * document the descriptor shows. Without them a co-signer would see text keys.
 */
@Tag("pack")
class ConsentTextsTest {

  private static final Path LOCALES =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/backend"))
          .resolve("../frontend/locales")
          .normalize();
  private static final List<String> REQUIRED =
      List.of(
          "title",
          "intro",
          "consent",
          "partiesHeading",
          "send",
          "sentTitle",
          "sentBody",
          "rejectedBody");

  @ParameterizedTest
  @ValueSource(strings = {"en", "ar"})
  void everyPurposeHasItsTexts(String lang) throws Exception {
    Map<?, ?> texts =
        new Yaml(new SafeConstructor(new LoaderOptions()))
            .load(Files.readString(LOCALES.resolve(lang).resolve("consent.json")));
    for (Descriptor d : ConsentCatalog.load("classpath*:consent/*.yaml").values()) {
      List<String> keys = new ArrayList<>(REQUIRED);
      d.details().keySet().forEach(name -> keys.add("details." + name));
      d.documents().keySet().forEach(name -> keys.add("documents." + name));
      for (String key : keys) {
        Object node = texts.get(d.purpose());
        for (String part : key.split(java.util.regex.Pattern.quote("."))) {
          node = node instanceof Map<?, ?> m ? m.get(part) : null;
        }
        assertTrue(
            node instanceof String s && !s.isBlank(),
            lang + "/consent.json lacks " + d.purpose() + "." + key);
      }
    }
  }
}
