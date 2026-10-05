package com.poc.backend.documents;

import static com.poc.backend.documents.DocumentStorage.safeFilename;

import com.poc.backend.storage.S3Properties;
import java.time.Duration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Mints the short-lived presigned GET for one stored document. Shared by the JWT documents API and
 * the public co-founder signing page, so both hand out the same 60-second URL and neither ever
 * returns the storage key. Callers decide who may see the document before calling this.
 */
@Component
public class DocumentDownloads {

  static final Duration GET_TTL = Duration.ofSeconds(60);

  private final S3Presigner presigner;
  private final S3Properties props;

  public DocumentDownloads(S3Presigner presigner, S3Properties props) {
    this.presigner = presigner;
    this.props = props;
  }

  public DownloadUrl mint(Document doc) {
    GetObjectRequest get =
        GetObjectRequest.builder()
            .bucket(props.getBucket())
            .key(doc.getS3Key())
            .responseContentDisposition(
                "attachment; filename=\"" + safeFilename(doc.getFilename()) + "\"")
            .build();
    PresignedGetObjectRequest presigned =
        presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(GET_TTL)
                .getObjectRequest(get)
                .build());
    return new DownloadUrl(presigned.url().toString(), GET_TTL.toSeconds());
  }

  public record DownloadUrl(String url, long expiresIn) {}
}
