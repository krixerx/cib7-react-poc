package com.poc.backend.documents;

import com.poc.backend.consent.ConsentCatalog;
import java.io.IOException;
import java.util.Map;

/** Test access to the pack's consent descriptors from the documents package. */
final class ConsentCatalogAccess {

  private ConsentCatalogAccess() {}

  static Map<String, ConsentCatalog.Descriptor> descriptors() throws IOException {
    ConsentCatalog catalog = new ConsentCatalog(new String[] {"classpath*:consent/*.yaml"});
    return catalog.all();
  }
}
