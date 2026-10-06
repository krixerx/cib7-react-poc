package com.poc.backend.documents;

import java.util.Set;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Rules shared by the user-facing and the internal documents controllers. The key checks are the
 * ownership rule of docs/security.md rule 6: an S3 key a caller hands in is only trusted when its
 * prefix binds it to that caller ({@code pending/<user>/}) or to a case the caller may access
 * ({@code process/<case>/}), and it has exactly the shape the backend itself mints.
 */
final class DocumentStorage {

  static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("application/pdf", "image/jpeg", "image/png");

  private DocumentStorage() {}

  /** {@code pending/<user>/<uuid>/<file>}, as minted by upload-url and stage for that user. */
  static boolean isPendingKeyOf(String key, String userId) {
    return hasOwnedShape(key, "pending", userId);
  }

  /** {@code process/<piId>/<uuid>/<file>}, as minted by upload-url and the internal endpoints. */
  static boolean isProcessKeyOf(String key, String processInstanceId) {
    return hasOwnedShape(key, "process", processInstanceId);
  }

  /**
   * Exactly four non-empty segments, the first two fixed. Rejecting {@code ..} and empty segments
   * keeps a key like {@code pending/bart/../homer/x} from passing a plain prefix test on a store
   * that normalizes paths.
   */
  private static boolean hasOwnedShape(String key, String root, String owner) {
    if (key == null || owner == null || owner.isBlank()) {
      return false;
    }
    String[] parts = key.split("/", -1);
    if (parts.length != 4 || !root.equals(parts[0]) || !owner.equals(parts[1])) {
      return false;
    }
    for (String part : parts) {
      if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
        return false;
      }
    }
    return true;
  }

  static boolean objectExists(S3Client s3, String bucket, String key) {
    try {
      return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()) != null;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) return false;
      throw e;
    }
  }

  static String safeFilename(String raw) {
    if (raw == null) return "file";
    String trimmed = raw.replaceAll("[^A-Za-z0-9._-]", "_");
    return trimmed.isBlank() || trimmed.matches("\\.+") ? "file" : trimmed;
  }
}
