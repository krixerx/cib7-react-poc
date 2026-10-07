package com.poc.backend.consent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.poc.backend.consent.ConsentCatalog.Descriptor;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The closed set a co-signing descriptor must stay inside; anything else stops startup. */
class ConsentCatalogTest {

  private static final String VALID =
      """
      platform: 2
      purpose: owner
      process: vehicleRegistration
      applicant: { firstName: firstName, lastName: lastName }
      variables:
        parties: additionalOwners
        confirmations: ownerConfirmations
        rejected: rejectedByOwner
        sent: sentToProcess
      messages: { signature: OwnerConfirmation, send: SendToProcess }
      wording:
        party: Owner
        rejection: rejected the application
        unknownLink: Unknown link.
        alreadyRejected: Already rejected.
        alreadySent: Already sent.
        alreadySigned: Already signed.
        notWaiting: Not waiting.
        rejectedBack: Rejected back.
        notReady: Not ready.
        notWaitingForSend: Not waiting for send.
      details:
        companyName: { variable: companyName, type: string }
      """;

  /** Every co-signing descriptor of the pack parses; load() refuses the first one that does not. */
  @Tag("pack")
  @Test
  void everyConsentDescriptorOfThePackLoads() throws Exception {
    for (Map.Entry<String, Descriptor> e :
        ConsentCatalog.load("classpath*:consent/*.yaml").entrySet()) {
      assertEquals(e.getKey(), e.getValue().config().purpose(), "purpose of " + e.getKey());
    }
  }

  @Test
  void validDescriptorParses() {
    Descriptor d = ConsentCatalog.parse(yaml(VALID));
    assertEquals("owner", d.config().purpose());
    assertEquals("Not ready.", d.messages().notReady());
  }

  @Test
  void anythingOutsideTheClosedSetIsRefused() {
    // A detail type that would put a raw value (personal codes, emails) on the public page.
    assertRefused(VALID.replace("type: string", "type: raw"));
    assertRefused(VALID.replace("purpose: owner", "purpose: ../internal"));
    assertRefused(VALID.replace("parties: additionalOwners", "parties: \"x' or 1=1\""));
    assertRefused(VALID.replace("  notReady: Not ready.\n", ""));
    assertRefused(VALID.replace("party: Owner", "party: \"" + "x".repeat(301) + "\""));
    assertRefused(VALID.replace("platform: 2", "platform: 1"));
  }

  private static void assertRefused(String descriptor) {
    assertThrows(
        IllegalArgumentException.class, () -> ConsentCatalog.parse(yaml(descriptor)), descriptor);
  }

  private static Object yaml(String text) {
    return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
  }
}
