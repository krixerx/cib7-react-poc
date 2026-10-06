package com.poc.backend.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.poc.backend.payment.FeeCatalog.Fee;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The pack's fee descriptors: what loads and what is refused (docs/platform-api.md). */
class FeeCatalogTest {

  private static final String HEAD =
      "platform: 2\nprocess: p\nservice: S\nrecipient: R\ncurrency: EUR\n";

  private static Object yaml(String text) {
    return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
  }

  @Tag("pack")
  @Test
  void everyFeeDescriptorOfThePackLoads() throws Exception {
    Map<String, Fee> fees = FeeCatalog.load("classpath*:payment/*.yaml");
    assertThat(fees).isNotNull();
  }

  @Test
  void tiersPickTheFirstLimitTheValueIsBelow() {
    Fee fee =
        FeeCatalog.parse(
            yaml(
                HEAD
                    + "amount:\n  tiers:\n    variable: price\n    below:\n"
                    + "      - { limit: 10, amount: 1 }\n      - { limit: 20, amount: 2.5 }\n"
                    + "    otherwise: 3\n"));
    assertThat(fee.amount().forValue(9.99)).isEqualByComparingTo("1");
    assertThat(fee.amount().forValue(10)).isEqualByComparingTo("2.5");
    assertThat(fee.amount().forValue(20)).isEqualByComparingTo("3");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "amount: { flat: 265 }\nscript: x\n",
        "amount: { flat: 265, tiers: { variable: v, below: [{limit: 1, amount: 1}], otherwise: 2 } }\n",
        "amount: { flat: -5 }\n",
        "amount: { flat: 1.005 }\n",
        "amount: { flat: '265' }\n",
        "amount: { tiers: { variable: v, below: [], otherwise: 2 } }\n",
        "amount: { tiers: { variable: v, below: [{limit: 20, amount: 1}, {limit: 10, amount: 2}], otherwise: 3 } }\n",
        "amount: { tiers: { variable: 'a b', below: [{limit: 1, amount: 1}], otherwise: 2 } }\n",
      })
  void aMalformedFeeIsRefused(String amount) {
    assertThrows(IllegalArgumentException.class, () -> FeeCatalog.parse(yaml(HEAD + amount)));
  }

  @Test
  void anotherPlatformOrCurrencyShapeIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FeeCatalog.parse(
                yaml(HEAD.replace("platform: 2", "platform: 1") + "amount: { flat: 1 }\n")));
    assertThrows(
        IllegalArgumentException.class,
        () -> FeeCatalog.parse(yaml(HEAD.replace("EUR", "euro") + "amount: { flat: 1 }\n")));
  }
}
