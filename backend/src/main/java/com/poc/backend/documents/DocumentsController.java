package com.poc.backend.documents;

import static com.poc.backend.documents.DocumentStorage.ALLOWED_CATEGORIES;
import static com.poc.backend.documents.DocumentStorage.ALLOWED_CONTENT_TYPES;
import static com.poc.backend.documents.DocumentStorage.isPendingKeyOf;
import static com.poc.backend.documents.DocumentStorage.isProcessKeyOf;
import static com.poc.backend.documents.DocumentStorage.objectExists;
import static com.poc.backend.documents.DocumentStorage.safeFilename;

import com.poc.backend.security.CaseAccessService;
import com.poc.backend.storage.S3Properties;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * REST surface for document upload, download, and listing. Moved out of the engine module: metadata
 * that used to live as engine {@code Attachment} rows is now the {@link Document} JPA entity, so
 * the engine carries no document concept at all.
 *
 * <p>Every endpoint here is JWT-authenticated (SPA, mobile, MCP); the uploader is recorded from
 * {@code preferred_username}. The engine's own document calls live in {@link
 * InternalDocumentsController} under {@code /api/internal/documents}.
 *
 * <p>Per-case authorization: every process-scoped read and write runs through {@link
 * CaseAccessService} — reviewers ({@code civil-servant}/{@code cib7-admin}) see every case,
 * applicants only the cases the engine says they started. Failures surface as 404 so probing for
 * case or attachment ids leaks nothing.
 *
 * <p>Object key layout:
 *
 * <pre>
 *   pending/{userId}/{uuid}/{safeFilename}   — staged uploads, expire in 24h
 *   process/{piId}/{uuid}/{safeFilename}     — process-scoped, kept forever
 * </pre>
 *
 * <p>{@link Document#getS3Key()} stores the S3 key (not a presigned URL, since those expire). The
 * presigned GET is minted on demand by {@code /attachments/{aid}/download-url}; keys of filed
 * documents are never returned to clients. A key a client hands in is accepted only under its own
 * {@code pending/<user>/} prefix or the case's {@code process/<piId>/} prefix (docs/security.md
 * rule 6).
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentsController {

  private static final Duration PUT_TTL = Duration.ofMinutes(5);

  private final S3Client s3;
  private final S3Presigner presigner;
  private final S3Properties props;
  private final DocumentRepository documents;
  private final CaseAccessService caseAccess;
  private final DocumentDownloads downloads;

  public DocumentsController(
      S3Client s3,
      S3Presigner presigner,
      S3Properties props,
      DocumentRepository documents,
      CaseAccessService caseAccess,
      DocumentDownloads downloads) {
    this.s3 = s3;
    this.presigner = presigner;
    this.props = props;
    this.documents = documents;
    this.caseAccess = caseAccess;
    this.downloads = downloads;
  }

  // ----------------- JWT-authenticated endpoints (SPA) -----------------

  @PostMapping("/upload-url")
  public ResponseEntity<?> mintUploadUrl(@RequestBody UploadUrlRequest req) {
    if (req == null || req.filename() == null || req.contentType() == null) {
      return badRequest("filename and contentType are required.");
    }
    if (!ALLOWED_CONTENT_TYPES.contains(req.contentType())) {
      return badRequest("contentType must be one of " + ALLOWED_CONTENT_TYPES);
    }
    if (req.size() <= 0 || req.size() > props.getMaxBytes()) {
      return badRequest("size must be between 1 and " + props.getMaxBytes() + " bytes.");
    }
    String scope = req.scope() == null ? "pending" : req.scope();

    String userId = currentUserId();
    if (userId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new ErrorResponse("no_user", "No authenticated user."));
    }

    String key;
    if ("process".equals(scope)) {
      if (req.scopeId() == null || req.scopeId().isBlank()) {
        return badRequest("scopeId (process instance id) is required for scope=process.");
      }
      if (!caseAccess.canAccessCase(req.scopeId())) {
        return caseNotFound();
      }
      key =
          "process/" + req.scopeId() + "/" + UUID.randomUUID() + "/" + safeFilename(req.filename());
    } else {
      // Default to pending — anyone authenticated can stage; the key
      // includes the user id and the lifecycle rule kills abandoned
      // objects after 24h.
      key = "pending/" + userId + "/" + UUID.randomUUID() + "/" + safeFilename(req.filename());
    }

    // The presigned PUT URL is anchored to specific request headers
    // (Content-Type, Content-Length). The browser MUST send exactly
    // those values or the signature check fails on RustFS — this is
    // the size/type enforcement, no extra server-side check needed.
    PutObjectRequest putRequest =
        PutObjectRequest.builder()
            .bucket(props.getBucket())
            .key(key)
            .contentType(req.contentType())
            .contentLength(req.size())
            .build();
    PresignedPutObjectRequest presigned =
        presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(PUT_TTL)
                .putObjectRequest(putRequest)
                .build());

    return ResponseEntity.ok(
        new UploadUrlResponse(
            key,
            presigned.url().toString(),
            Map.of("Content-Type", req.contentType()),
            PUT_TTL.toSeconds()));
  }

  /**
   * MCP-friendly "server-side staging" endpoint. The SPA uses {@link
   * #mintUploadUrl(UploadUrlRequest)} + a direct presigned PUT so it can render an upload progress
   * bar; that flow does not work for the MCP sidecar because the presigned URL is anchored to
   * {@code S3_PUBLIC_ENDPOINT} (which resolves to the host's {@code localhost:9000} — unreachable
   * from inside the MCP container).
   *
   * <p>This endpoint accepts {@code { category, filename, contentType, base64 }} directly, decodes
   * the base64 server-side, writes the bytes to the {@code pending/} prefix, and returns the
   * resulting {@code pendingKey}. The caller (an MCP tool, typically) then passes {@code {
   * pendingKey, filename, contentType }} into {@code complete_task} as the value of {@code
   * pendingIdDocument}; the BPMN's existing {@code Task_AttachIdDocument} step picks it up and
   * promotes the object to {@code process/} scope with a {@link Document} row.
   */
  @PostMapping("/stage")
  public ResponseEntity<?> stagePending(@RequestBody StagePendingRequest req) {
    if (req == null
        || req.filename() == null
        || req.contentType() == null
        || req.category() == null
        || req.base64() == null) {
      return badRequest("filename, contentType, category, and base64 are required.");
    }
    if (!ALLOWED_CONTENT_TYPES.contains(req.contentType())) {
      return badRequest("contentType must be one of " + ALLOWED_CONTENT_TYPES);
    }
    if (!ALLOWED_CATEGORIES.contains(req.category())) {
      return badRequest("category must be one of " + ALLOWED_CATEGORIES);
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(req.base64());
    } catch (IllegalArgumentException e) {
      return badRequest("base64 was not decodable.");
    }
    if (bytes.length == 0 || bytes.length > props.getMaxBytes()) {
      return badRequest("decoded size must be between 1 and " + props.getMaxBytes() + " bytes.");
    }
    String userId = currentUserId();
    if (userId == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(new ErrorResponse("no_user", "No authenticated user."));
    }
    String key = "pending/" + userId + "/" + UUID.randomUUID() + "/" + safeFilename(req.filename());
    s3.putObject(
        PutObjectRequest.builder()
            .bucket(props.getBucket())
            .key(key)
            .contentType(req.contentType())
            .contentLength((long) bytes.length)
            .build(),
        software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    return ResponseEntity.ok(new StagePendingResponse(key, req.filename(), req.contentType()));
  }

  @PostMapping("/{processInstanceId}/attachments")
  public ResponseEntity<?> registerAttachment(
      @PathVariable String processInstanceId, @RequestBody AttachmentRegisterRequest req) {
    if (req == null
        || req.key() == null
        || req.filename() == null
        || req.contentType() == null
        || req.category() == null) {
      return badRequest("key, filename, contentType, and category are required.");
    }
    if (!ALLOWED_CATEGORIES.contains(req.category())) {
      return badRequest("category must be one of " + ALLOWED_CATEGORIES);
    }
    if (!caseAccess.canAccessCase(processInstanceId)) {
      return caseNotFound();
    }
    // The key must be one this caller staged, or one already filed under this case. Any other key
    // (another user's pending upload, another case's object) gets the same answer as a missing
    // object, so key probing learns nothing.
    String userId = currentUserId();
    boolean owned =
        isPendingKeyOf(req.key(), userId) || isProcessKeyOf(req.key(), processInstanceId);
    if (!owned || !objectExists(s3, props.getBucket(), req.key())) {
      return badRequest("Object not found in storage. Did the upload complete?");
    }
    Document doc =
        documents.save(
            new Document(
                processInstanceId,
                req.category(),
                req.filename(),
                req.contentType(),
                req.key(),
                userId));
    return ResponseEntity.ok(new AttachmentResponse(doc.getId()));
  }

  /**
   * Every document of every case the caller started, newest first — the applicant's "My files"
   * page, so a certificate is one click away instead of one per case. Scoped through {@link
   * CaseAccessService#ownCaseIds()}, never through an id the client passes.
   */
  @GetMapping("/mine")
  public ResponseEntity<?> listMyDocuments() {
    List<String> caseIds = caseAccess.ownCaseIds();
    if (caseIds.isEmpty()) {
      return ResponseEntity.ok(List.of());
    }
    List<CaseDocumentEntry> out =
        documents.findByProcessInstanceIdInOrderByCreatedAtDesc(caseIds).stream()
            .map(
                d ->
                    new CaseDocumentEntry(
                        d.getId(),
                        d.getProcessInstanceId(),
                        d.getCategory(),
                        d.getFilename(),
                        d.getContentType(),
                        DateTimeFormatter.ISO_INSTANT.format(d.getCreatedAt())))
            .toList();
    return ResponseEntity.ok(out);
  }

  @GetMapping("/{processInstanceId}")
  public ResponseEntity<?> listAttachments(@PathVariable String processInstanceId) {
    if (!caseAccess.canAccessCase(processInstanceId)) {
      return caseNotFound();
    }
    List<DocumentEntry> out =
        documents.findByProcessInstanceIdOrderByCreatedAtAsc(processInstanceId).stream()
            .map(
                d ->
                    new DocumentEntry(
                        d.getId(),
                        d.getCategory(),
                        d.getFilename(),
                        d.getContentType(),
                        DateTimeFormatter.ISO_INSTANT.format(d.getCreatedAt()),
                        d.getUploaderUserId()))
            .toList();
    return ResponseEntity.ok(out);
  }

  @GetMapping("/attachments/{attachmentId}/download-url")
  public ResponseEntity<?> mintDownloadUrl(@PathVariable String attachmentId) {
    Document doc = documents.findById(attachmentId).orElse(null);
    // Same 404 for "unknown id" and "not your case" — no existence probing.
    if (doc == null || !caseAccess.canAccessCase(doc.getProcessInstanceId())) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(new ErrorResponse("not_found", "No such attachment."));
    }
    return ResponseEntity.ok(downloads.mint(doc));
  }

  // ----------------- helpers -----------------

  /** Keycloak username from the validated Bearer; null on the internal chain. */
  private static String currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken jwt) {
      return jwt.getToken().getClaimAsString("preferred_username");
    }
    return null;
  }

  private static ResponseEntity<?> badRequest(String message) {
    return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", message));
  }

  /** 404 for cases the caller may not access — indistinguishable from a missing case. */
  private static ResponseEntity<?> caseNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse("not_found", "No such case."));
  }

  // ----------------- DTOs -----------------

  public record UploadUrlRequest(
      String filename, String contentType, long size, String scope, String scopeId) {}

  public record UploadUrlResponse(
      String key, String url, Map<String, String> headers, long expiresIn) {}

  public record AttachmentRegisterRequest(
      String key, String filename, String contentType, String category) {}

  /** Only the id: storage keys stay server-side, downloads go through download-url. */
  public record AttachmentResponse(String attachmentId) {}

  public record DocumentEntry(
      String id,
      String category,
      String filename,
      String contentType,
      String createdAt,
      String uploaderUserId) {}

  /** A document plus the case it belongs to, for the cross-case "My files" list. */
  public record CaseDocumentEntry(
      String id,
      String processInstanceId,
      String category,
      String filename,
      String contentType,
      String createdAt) {}

  public record StagePendingRequest(
      String filename, String contentType, String category, String base64) {}

  public record StagePendingResponse(String pendingKey, String filename, String contentType) {}

  public record ErrorResponse(String code, String message) {}
}
