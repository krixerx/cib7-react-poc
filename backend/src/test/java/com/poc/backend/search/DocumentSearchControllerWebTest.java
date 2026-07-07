package com.poc.backend.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.security.CaseAccessService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for the semantic search endpoint: request validation, the per-case access filter
 * (a hit from a case the caller may not access is silently dropped — same no-probing posture as the
 * rest of the documents API), and the per-request memoization of case-access lookups.
 *
 * <p>Security filters are off, mirroring {@code DocumentsControllerWebTest}; authorization here is
 * entirely the {@link CaseAccessService} collaborator, which is mocked per test.
 */
@WebMvcTest(controllers = DocumentSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
class DocumentSearchControllerWebTest {

  @Autowired MockMvc mvc;
  @MockitoBean VectorStore vectorStore;
  @MockitoBean CaseAccessService caseAccess;

  private static Document chunk(String pi, String attachmentId, String text) {
    return Document.builder()
        .text(text)
        .metadata(
            Map.of(
                "processInstanceId",
                pi,
                "attachmentId",
                attachmentId,
                "category",
                "generated-certificate",
                "filename",
                "certificate.pdf"))
        .score(0.9)
        .build();
  }

  @Test
  void missingQueryIsBadRequest() throws Exception {
    mvc.perform(get("/api/documents/search"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("bad_request"));
  }

  @Test
  void blankQueryIsBadRequest() throws Exception {
    mvc.perform(get("/api/documents/search").param("q", "   ")).andExpect(status().isBadRequest());
  }

  @Test
  void hitsFromInaccessibleCasesAreDropped() throws Exception {
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                chunk("pi-mine", "att-1", "certificate of registration for plate 123"),
                chunk("pi-other", "att-2", "someone else's certificate")));
    when(caseAccess.canAccessCase("pi-mine")).thenReturn(true);
    when(caseAccess.canAccessCase("pi-other")).thenReturn(false);

    mvc.perform(get("/api/documents/search").param("q", "certificate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.results[0].attachmentId").value("att-1"))
        .andExpect(jsonPath("$.results[0].processInstanceId").value("pi-mine"))
        .andExpect(jsonPath("$.results[0].snippet").isNotEmpty())
        .andExpect(jsonPath("$.results[0].score").value(0.9));
  }

  @Test
  void caseAccessIsCheckedOncePerCaseNotPerChunk() throws Exception {
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                chunk("pi-mine", "att-1", "chunk one"),
                chunk("pi-mine", "att-1", "chunk two"),
                chunk("pi-mine", "att-2", "chunk three")));
    when(caseAccess.canAccessCase("pi-mine")).thenReturn(true);

    mvc.perform(get("/api/documents/search").param("q", "chunks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(3));

    verify(caseAccess, times(1)).canAccessCase("pi-mine");
  }

  @Test
  void chunksWithoutProcessInstanceMetadataAreDropped() throws Exception {
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(Document.builder().text("orphan chunk").score(0.5).build()));

    mvc.perform(get("/api/documents/search").param("q", "orphan"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }
}
