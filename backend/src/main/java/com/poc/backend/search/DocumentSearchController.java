package com.poc.backend.search;

import com.poc.backend.security.CaseAccessService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Semantic search over the extracted text of stored documents. Lives under {@code /api/documents}
 * so it rides SecurityConfig's existing JWT chain (the literal {@code /search} path wins over
 * DocumentsController's {@code /{processInstanceId}} template on mapping resolution).
 *
 * <p>Authorization is over-fetch-then-post-filter: the vector store returns a candidate pool, and
 * every hit runs through {@link CaseAccessService} before it may be returned — the same per-case
 * gate as the rest of the documents API, so an applicant can never surface chunks from someone
 * else's case no matter what the index holds. Per-request memoization keeps that at one engine
 * lookup per distinct case in the pool.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentSearchController {

  /** Candidate pool fetched from the index before the access filter prunes it. */
  private static final int CANDIDATE_POOL = 20;

  /** Maximum hits returned to the caller after filtering. */
  private static final int MAX_RESULTS = 5;

  /** Snippet cap — hits carry a preview, not the whole chunk. */
  private static final int SNIPPET_CHARS = 300;

  private final VectorStore vectorStore;
  private final CaseAccessService caseAccess;

  public DocumentSearchController(VectorStore vectorStore, CaseAccessService caseAccess) {
    this.vectorStore = vectorStore;
    this.caseAccess = caseAccess;
  }

  @GetMapping("/search")
  public ResponseEntity<?> search(@RequestParam(name = "q", required = false) String q) {
    if (q == null || q.isBlank()) {
      return ResponseEntity.badRequest()
          .body(new ErrorResponse("bad_request", "Query parameter q is required."));
    }

    List<Document> candidates =
        vectorStore.similaritySearch(SearchRequest.builder().query(q).topK(CANDIDATE_POOL).build());

    Map<String, Boolean> accessByCase = new HashMap<>();
    List<SearchHit> hits =
        (candidates == null ? List.<Document>of() : candidates)
            .stream()
                .filter(
                    d -> {
                      Object pi = d.getMetadata().get("processInstanceId");
                      return pi instanceof String id
                          && accessByCase.computeIfAbsent(id, caseAccess::canAccessCase);
                    })
                .limit(MAX_RESULTS)
                .map(DocumentSearchController::toHit)
                .toList();

    return ResponseEntity.ok(new SearchResponse(q, hits.size(), hits));
  }

  private static SearchHit toHit(Document d) {
    String text = d.getText() == null ? "" : d.getText().strip();
    String snippet = text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS) + "…";
    return new SearchHit(
        (String) d.getMetadata().get("attachmentId"),
        (String) d.getMetadata().get("processInstanceId"),
        (String) d.getMetadata().get("category"),
        (String) d.getMetadata().get("filename"),
        snippet,
        d.getScore());
  }

  public record SearchHit(
      String attachmentId,
      String processInstanceId,
      String category,
      String filename,
      String snippet,
      Double score) {}

  public record SearchResponse(String query, int count, List<SearchHit> results) {}

  public record ErrorResponse(String code, String message) {}
}
