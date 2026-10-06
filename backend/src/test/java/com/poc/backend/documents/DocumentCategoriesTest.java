package com.poc.backend.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.poc.backend.documents.DocumentCategories.By;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The pack's document categories (docs/platform-api.md). */
class DocumentCategoriesTest {

  @Tag("pack")
  @Test
  void thePacksCategoriesLoadBesideTheCoresCertificate() throws Exception {
    Map<String, By> all = DocumentCategories.load("classpath*:documents.json");
    assertThat(all).containsEntry(DocumentCategories.CERTIFICATE, By.SYSTEM);
  }

  /** A co-signer may download only an applicant upload the pack declares. */
  @Tag("pack")
  @Test
  void consentDocumentsAreDeclaredApplicantCategories() throws Exception {
    Map<String, By> all = DocumentCategories.load("classpath*:documents.json");
    for (var descriptor : ConsentCatalogAccess.descriptors().values()) {
      for (var document : descriptor.documents().values()) {
        assertThat(all.get(document.category()))
            .as(descriptor.purpose() + ": " + document.category())
            .isEqualTo(By.APPLICANT);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"platform\": 2, \"categories\": {\"generated-certificate\": {\"by\": \"system\"}}}",
        "{\"platform\": 2, \"categories\": {\"generated-x\": {\"by\": \"applicant\"}}}",
        "{\"platform\": 2, \"categories\": {\"id-card\": {\"by\": \"system\"}}}",
        "{\"platform\": 2, \"categories\": {\"id-card\": {\"by\": \"anyone\"}}}",
        "{\"platform\": 2, \"categories\": {\"Id Card\": {\"by\": \"applicant\"}}}",
        "{\"platform\": 2, \"categories\": {}, \"script\": 1}",
        "{\"platform\": 1, \"categories\": {}}",
      })
  void aMalformedOrDangerousDeclarationIsRefused(String json) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DocumentCategories.parse(
                new Yaml(new SafeConstructor(new LoaderOptions())).load(json)));
  }
}
