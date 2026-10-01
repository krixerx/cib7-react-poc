package com.poc.backend.documents;

import static com.poc.backend.documents.DocumentStorage.ALLOWED_CATEGORIES;
import static com.poc.backend.documents.DocumentStorage.isPendingKeyOf;
import static com.poc.backend.documents.DocumentStorage.objectExists;
import static com.poc.backend.documents.DocumentStorage.safeFilename;

import com.poc.backend.documents.DocumentsController.AttachmentResponse;
import com.poc.backend.documents.DocumentsController.ErrorResponse;
import com.poc.backend.engine.EngineClient;
import com.poc.backend.storage.S3Properties;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Document endpoints only the engine calls, through the ESB, authenticated by {@code
 * X-Internal-Token} on {@code /api/internal/**} (see {@link
 * com.poc.backend.security.SecurityConfig}).
 *
 * <p>{@code move-pending} still treats its input as untrusted: the pending key comes from a process
 * variable the applicant's form wrote, so it is only moved when it lies under the case initiator's
 * own {@code pending/<user>/} prefix. Otherwise an applicant could name another user's staged
 * upload and have the engine adopt it into their case.
 */
@RestController
@RequestMapping("/api/internal/documents")
public class InternalDocumentsController {

  private final S3Client s3;
  private final S3Properties props;
  private final DocumentRepository documents;
  private final EngineClient engine;

  public InternalDocumentsController(
      S3Client s3, S3Properties props, DocumentRepository documents, EngineClient engine) {
    this.s3 = s3;
    this.props = props;
    this.documents = documents;
    this.engine = engine;
  }

  @PostMapping("/move-pending")
  public ResponseEntity<?> movePending(@RequestBody MovePendingRequest req) {
    if (req == null
        || req.pendingKey() == null
        || req.processInstanceId() == null
        || req.filename() == null
        || req.contentType() == null
        || req.category() == null) {
      return badRequest("pendingKey, processInstanceId, filename, contentType, category required.");
    }
    if (!ALLOWED_CATEGORIES.contains(req.category())) {
      return badRequest("category must be one of " + ALLOWED_CATEGORIES);
    }
    String initiator = engine.getHistoricStartUserId(req.processInstanceId());
    if (!isPendingKeyOf(req.pendingKey(), initiator)) {
      return badRequest("pendingKey must live under the case initiator's pending/ prefix.");
    }
    if (!objectExists(s3, props.getBucket(), req.pendingKey())) {
      return badRequest("Pending object not found — already migrated or never uploaded?");
    }

    String destKey =
        "process/"
            + req.processInstanceId()
            + "/"
            + UUID.randomUUID()
            + "/"
            + safeFilename(req.filename());

    s3.copyObject(
        CopyObjectRequest.builder()
            .sourceBucket(props.getBucket())
            .sourceKey(req.pendingKey())
            .destinationBucket(props.getBucket())
            .destinationKey(destKey)
            .build());
    s3.deleteObject(
        DeleteObjectRequest.builder().bucket(props.getBucket()).key(req.pendingKey()).build());

    Document doc =
        documents.save(
            new Document(
                req.processInstanceId(),
                req.category(),
                req.filename(),
                req.contentType(),
                destKey,
                initiator));
    return ResponseEntity.ok(new AttachmentResponse(doc.getId()));
  }

  @PostMapping("/server-upload")
  public ResponseEntity<?> serverUpload(@RequestBody ServerUploadRequest req) {
    if (req == null
        || req.processInstanceId() == null
        || req.filename() == null
        || req.contentType() == null
        || req.category() == null
        || req.base64() == null) {
      return badRequest("processInstanceId, filename, contentType, category, base64 required.");
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
    // Same cap as /stage: the caller is the trusted engine, but a runaway
    // FreeMarker payload must not buffer unbounded bytes in memory.
    if (bytes.length == 0 || bytes.length > props.getMaxBytes()) {
      return badRequest("decoded size must be between 1 and " + props.getMaxBytes() + " bytes.");
    }

    String key =
        "process/"
            + req.processInstanceId()
            + "/"
            + UUID.randomUUID()
            + "/"
            + safeFilename(req.filename());

    s3.putObject(
        PutObjectRequest.builder()
            .bucket(props.getBucket())
            .key(key)
            .contentType(req.contentType())
            .contentLength((long) bytes.length)
            .build(),
        software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));

    Document doc =
        documents.save(
            new Document(
                req.processInstanceId(),
                req.category(),
                req.filename(),
                req.contentType(),
                key,
                null));
    return ResponseEntity.ok(new AttachmentResponse(doc.getId()));
  }

  private static ResponseEntity<?> badRequest(String message) {
    return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", message));
  }

  public record MovePendingRequest(
      String pendingKey,
      String processInstanceId,
      String filename,
      String contentType,
      String category) {}

  public record ServerUploadRequest(
      String processInstanceId,
      String filename,
      String contentType,
      String category,
      String base64) {}
}
