package com.poc.backend.documents;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Metadata for one stored document — the business-side replacement for the
 * engine {@code Attachment} rows the old in-engine controller created via
 * {@code TaskService}. The bytes live in RustFS under {@link #s3Key}; this
 * row carries everything the SPA's Documents sidebar renders.
 *
 * <p>Keyed by a UUID string (the old attachment id was opaque too, so the
 * SPA contract is unchanged). {@code processInstanceId} links the document
 * to its case; the engine itself no longer knows documents exist.
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_pi", columnList = "processInstanceId")
})
public class Document {

    @Id
    private String id;

    @Column(nullable = false)
    private String processInstanceId;

    /** One of the controller's ALLOWED_CATEGORIES, e.g. applicant-id-document. */
    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String contentType;

    /** Object key in the RustFS bucket (process/{piId}/{uuid}/{filename}). */
    @Column(nullable = false, length = 1024)
    private String s3Key;

    /** Keycloak username of the uploader; null for engine-generated documents. */
    private String uploaderUserId;

    @Column(nullable = false)
    private Instant createdAt;

    protected Document() {
        // JPA
    }

    public Document(String processInstanceId, String category, String filename,
                    String contentType, String s3Key, String uploaderUserId) {
        this.id = UUID.randomUUID().toString();
        this.processInstanceId = processInstanceId;
        this.category = category;
        this.filename = filename;
        this.contentType = contentType;
        this.s3Key = s3Key;
        this.uploaderUserId = uploaderUserId;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProcessInstanceId() { return processInstanceId; }
    public String getCategory() { return category; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public String getS3Key() { return s3Key; }
    public String getUploaderUserId() { return uploaderUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
