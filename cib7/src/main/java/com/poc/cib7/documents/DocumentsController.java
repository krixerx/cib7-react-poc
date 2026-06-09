package com.poc.cib7.documents;

import java.io.IOException;
import java.net.URLConnection;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.task.Attachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * REST surface for document upload, download, and listing.
 *
 * <p>Two distinct authentication paths in here, gated by
 * {@link DocumentsApiSecurityConfig}:
 *
 * <ul>
 *   <li>JWT-authenticated endpoints (called by the SPA) — the
 *       {@link KeycloakAuthenticationFilter} has already pushed the user into
 *       {@link IdentityService}, so engine permission checks "just work".</li>
 *   <li>Internal endpoints {@code /move-pending} + {@code /server-upload} —
 *       called by BPMN service tasks via the cibseven http-connector. Auth is
 *       by shared header; no engine identity context.</li>
 * </ul>
 *
 * <p>Object key layout:
 * <pre>
 *   pending/{userId}/{uuid}/{safeFilename}   — staged uploads, expire in 24h
 *   process/{piId}/{uuid}/{safeFilename}     — process-scoped, kept forever
 * </pre>
 *
 * <p>{@link Attachment#getUrl()} stores the S3 key (not a presigned URL,
 * since those expire). The presigned GET is minted on demand by
 * {@code /attachments/{aid}/download-url}.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentsController {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentsController.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png");

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "applicant-id-document", "generated-approval-pdf", "generated-certificate");

    private static final Duration PUT_TTL = Duration.ofMinutes(5);
    private static final Duration GET_TTL = Duration.ofSeconds(60);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties props;
    private final TaskService taskService;
    private final IdentityService identityService;

    public DocumentsController(S3Client s3, S3Presigner presigner, S3Properties props,
                               TaskService taskService, IdentityService identityService) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
        this.taskService = taskService;
        this.identityService = identityService;
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
            key = "process/" + req.scopeId() + "/" + UUID.randomUUID() + "/" + safeFilename(req.filename());
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
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .contentType(req.contentType())
                .contentLength(req.size())
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(PUT_TTL)
                .putObjectRequest(putRequest)
                .build());

        return ResponseEntity.ok(new UploadUrlResponse(
                key,
                presigned.url().toString(),
                Map.of("Content-Type", req.contentType()),
                PUT_TTL.toSeconds()));
    }

    @PostMapping("/{processInstanceId}/attachments")
    public ResponseEntity<?> registerAttachment(@PathVariable String processInstanceId,
                                                @RequestBody AttachmentRegisterRequest req) {
        if (req == null || req.key() == null || req.filename() == null
                || req.contentType() == null || req.category() == null) {
            return badRequest("key, filename, contentType, and category are required.");
        }
        if (!ALLOWED_CATEGORIES.contains(req.category())) {
            return badRequest("category must be one of " + ALLOWED_CATEGORIES);
        }
        if (!objectExists(req.key())) {
            return badRequest("Object not found in storage. Did the upload complete?");
        }
        try {
            Attachment a = taskService.createAttachment(
                    req.category(), null, processInstanceId, req.filename(), "", req.key());
            return ResponseEntity.ok(new AttachmentResponse(a.getId(), req.key()));
        } catch (ProcessEngineException e) {
            LOG.warn("Engine refused createAttachment on PI {}: {}", processInstanceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("engine_denied", e.getMessage()));
        }
    }

    @GetMapping("/{processInstanceId}")
    public ResponseEntity<?> listAttachments(@PathVariable String processInstanceId) {
        try {
            List<Attachment> raw = taskService.getProcessInstanceAttachments(processInstanceId);
            List<DocumentEntry> out = new ArrayList<>(raw.size());
            for (Attachment a : raw) {
                out.add(new DocumentEntry(
                        a.getId(),
                        a.getType(),
                        a.getName(),
                        contentTypeFromName(a.getName()),
                        a.getCreateTime() == null ? null
                                : DateTimeFormatter.ISO_INSTANT.format(a.getCreateTime().toInstant()),
                        // The engine's Attachment interface has no getUserId
                        // (the uploader is stored against the task, not the
                        // attachment), so we don't surface a per-attachment
                        // uploader. Leave the SPA field null for now; we can
                        // backfill from history if it becomes useful.
                        null,
                        a.getUrl()));
            }
            return ResponseEntity.ok(out);
        } catch (ProcessEngineException e) {
            LOG.warn("Engine refused getProcessInstanceAttachments on PI {}: {}", processInstanceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("engine_denied", e.getMessage()));
        }
    }

    @GetMapping("/attachments/{attachmentId}/download-url")
    public ResponseEntity<?> mintDownloadUrl(@PathVariable String attachmentId) {
        // The attachment id is opaque; we have to look it up first to learn
        // which PI it lives on. The engine itself runs the read permission
        // check, so a user without access to the PI gets a ProcessEngineException
        // here even before we know which key to sign.
        Attachment a;
        try {
            a = taskService.getAttachment(attachmentId);
        } catch (ProcessEngineException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ErrorResponse("engine_denied", e.getMessage()));
        }
        if (a == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("not_found", "No such attachment."));
        }

        String key = a.getUrl();
        if (key == null || key.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("not_found", "Attachment has no storage key."));
        }

        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .responseContentDisposition("attachment; filename=\"" + safeFilename(a.getName()) + "\"")
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(GET_TTL)
                .getObjectRequest(get)
                .build());
        return ResponseEntity.ok(new DownloadUrlResponse(presigned.url().toString(), GET_TTL.toSeconds()));
    }

    // ----------------- Internal endpoints (BPMN engine) -----------------

    @PostMapping("/move-pending")
    public ResponseEntity<?> movePending(@RequestBody MovePendingRequest req) {
        if (req == null || req.pendingKey() == null || req.processInstanceId() == null
                || req.filename() == null || req.contentType() == null || req.category() == null) {
            return badRequest("pendingKey, processInstanceId, filename, contentType, category required.");
        }
        if (!ALLOWED_CATEGORIES.contains(req.category())) {
            return badRequest("category must be one of " + ALLOWED_CATEGORIES);
        }
        if (!req.pendingKey().startsWith("pending/")) {
            return badRequest("pendingKey must live under pending/");
        }
        if (!objectExists(req.pendingKey())) {
            return badRequest("Pending object not found — already migrated or never uploaded?");
        }

        String destKey = "process/" + req.processInstanceId()
                + "/" + UUID.randomUUID() + "/" + safeFilename(req.filename());

        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(props.getBucket())
                .sourceKey(req.pendingKey())
                .destinationBucket(props.getBucket())
                .destinationKey(destKey)
                .build());
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.getBucket())
                .key(req.pendingKey())
                .build());

        Attachment a = taskService.createAttachment(
                req.category(), null, req.processInstanceId(),
                req.filename(), "", destKey);
        return ResponseEntity.ok(new AttachmentResponse(a.getId(), destKey));
    }

    @PostMapping("/server-upload")
    public ResponseEntity<?> serverUpload(@RequestBody ServerUploadRequest req) {
        if (req == null || req.processInstanceId() == null || req.filename() == null
                || req.contentType() == null || req.category() == null || req.base64() == null) {
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
        if (bytes.length == 0) {
            return badRequest("base64 decoded to zero bytes.");
        }

        String key = "process/" + req.processInstanceId()
                + "/" + UUID.randomUUID() + "/" + safeFilename(req.filename());

        s3.putObject(PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .contentType(req.contentType())
                .contentLength((long) bytes.length)
                .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));

        Attachment a = taskService.createAttachment(
                req.category(), null, req.processInstanceId(),
                req.filename(), "", key);
        return ResponseEntity.ok(new AttachmentResponse(a.getId(), key));
    }

    // ----------------- helpers -----------------

    private boolean objectExists(String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
            return head != null;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    private String currentUserId() {
        return identityService.getCurrentAuthentication() == null
                ? null
                : identityService.getCurrentAuthentication().getUserId();
    }

    private static String safeFilename(String raw) {
        if (raw == null) return "file";
        String trimmed = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return trimmed.isBlank() ? "file" : trimmed;
    }

    private static String contentTypeFromName(String name) {
        if (name == null) return "application/octet-stream";
        String guess = URLConnection.guessContentTypeFromName(name);
        return guess != null ? guess : "application/octet-stream";
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", message));
    }

    // ----------------- DTOs -----------------

    public record UploadUrlRequest(String filename, String contentType, long size,
                                    String scope, String scopeId) {}

    public record UploadUrlResponse(String key, String url,
                                     Map<String, String> headers, long expiresIn) {}

    public record AttachmentRegisterRequest(String key, String filename,
                                             String contentType, String category) {}

    public record AttachmentResponse(String attachmentId, String key) {}

    public record DocumentEntry(String id, String category, String filename,
                                 String contentType, String createdAt,
                                 String uploaderUserId, String key) {}

    public record DownloadUrlResponse(String url, long expiresIn) {}

    public record MovePendingRequest(String pendingKey, String processInstanceId,
                                      String filename, String contentType, String category) {}

    public record ServerUploadRequest(String processInstanceId, String filename,
                                       String contentType, String category, String base64) {}

    public record ErrorResponse(String code, String message) {}
}
