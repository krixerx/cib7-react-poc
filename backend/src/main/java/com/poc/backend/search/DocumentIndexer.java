package com.poc.backend.search;

import com.poc.backend.documents.Document;
import com.poc.backend.storage.S3Properties;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Feeds newly registered documents into the vector index: fetch bytes from RustFS, extract text
 * with Tika, split into chunks, embed, store. Called (async) from every path that creates a {@link
 * Document} row; rows are immutable and get a fresh UUID per upload, so there is no re-index /
 * stale-chunk case to handle.
 *
 * <p>Indexing is best-effort by design — a failure here must never fail the upload, so everything
 * is caught and logged. Scanned images (JPEG/PNG without a text layer) yield no text from Tika and
 * are skipped; OCR is out of scope for the POC.
 */
@Service
public class DocumentIndexer {

  private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

  private final S3Client s3;
  private final S3Properties props;
  private final VectorStore vectorStore;

  public DocumentIndexer(S3Client s3, S3Properties props, VectorStore vectorStore) {
    this.s3 = s3;
    this.props = props;
    this.vectorStore = vectorStore;
  }

  @Async
  public void index(Document meta) {
    try {
      byte[] bytes =
          s3.getObjectAsBytes(
                  GetObjectRequest.builder().bucket(props.getBucket()).key(meta.getS3Key()).build())
              .asByteArray();

      // Tika sniffs the format from content + filename; the anonymous
      // subclass supplies the filename ByteArrayResource doesn't have.
      Resource resource =
          new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
              return meta.getFilename();
            }
          };

      List<org.springframework.ai.document.Document> extracted =
          new TikaDocumentReader(resource)
              .get().stream().filter(d -> d.getText() != null && !d.getText().isBlank()).toList();
      if (extracted.isEmpty()) {
        log.info(
            "No extractable text in attachment {} ({}) — skipped (scanned image?).",
            meta.getId(),
            meta.getFilename());
        return;
      }

      // Metadata goes on before splitting — TokenTextSplitter copies it onto
      // every chunk, and the search endpoint authorizes on processInstanceId.
      extracted.forEach(
          d ->
              d.getMetadata()
                  .putAll(
                      Map.of(
                          "attachmentId", meta.getId(),
                          "processInstanceId", meta.getProcessInstanceId(),
                          "category", meta.getCategory(),
                          "filename", meta.getFilename())));

      List<org.springframework.ai.document.Document> chunks =
          new TokenTextSplitter().apply(extracted);
      vectorStore.add(chunks);
      log.info(
          "Indexed attachment {} ({}) as {} chunk(s).",
          meta.getId(),
          meta.getFilename(),
          chunks.size());
    } catch (Exception e) {
      log.warn(
          "Indexing failed for attachment {} ({}): {}",
          meta.getId(),
          meta.getFilename(),
          e.getMessage());
    }
  }
}
