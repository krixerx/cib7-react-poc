package com.poc.backend.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.storage.S3Properties;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Web-slice tests for the engine-only document endpoints. Security filters are off here; the {@code
 * X-Internal-Token} gate is covered by {@code ApiEndpointClassesTest}. What matters below is that
 * the engine's input is still checked: a pending key is only moved out of the case initiator's own
 * prefix.
 */
@WebMvcTest(controllers = InternalDocumentsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({InternalDocumentsControllerWebTest.Props.class, DocumentCategories.class})
@TestPropertySource(properties = {"app.s3.bucket=test-bucket", "app.s3.max-bytes=1024"})
class InternalDocumentsControllerWebTest {

  private static final String PI = "pi-42";
  private static final String INITIATOR = "lisa";
  private static final String UUID_RE = "[0-9a-f-]{36}";

  @TestConfiguration
  @EnableConfigurationProperties(S3Properties.class)
  static class Props {}

  @Autowired MockMvc mvc;
  @MockitoBean S3Client s3;
  @MockitoBean DocumentRepository documents;
  @MockitoBean EngineClient engine;

  @BeforeEach
  void stubCollaborators() {
    when(documents.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(engine.getHistoricStartUserId(PI)).thenReturn(INITIATOR);
  }

  private void givenObjectExists() {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());
  }

  private static String moveBody(String pendingKey) {
    return "{\"pendingKey\":\""
        + pendingKey
        + "\",\"processInstanceId\":\""
        + PI
        + "\",\"filename\":\"id.png\",\"contentType\":\"image/png\","
        + "\"category\":\"applicant-id-document\"}";
  }

  private static String uploadBody(int byteCount) {
    return "{\"processInstanceId\":\""
        + PI
        + "\",\"filename\":\"approval.pdf\",\"contentType\":\"application/pdf\","
        + "\"category\":\"generated-approval-pdf\",\"base64\":\""
        + Base64.getEncoder().encodeToString(new byte[byteCount])
        + "\"}";
  }

  /** The engine files rendered PDFs as system documents and moves uploads as applicant ones. */
  @Test
  void eachInternalEndpointTakesOnlyItsKindOfCategory() throws Exception {
    givenObjectExists();
    mvc.perform(
            post("/api/internal/documents/server-upload")
                .contentType(MediaType.APPLICATION_JSON)
                .content(uploadBody(8).replace("generated-approval-pdf", "applicant-id-document")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/internal/documents/move-pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    moveBody("pending/lisa/u/id.png")
                        .replace("applicant-id-document", "generated-certificate")))
        .andExpect(status().isBadRequest());
    verify(documents, never()).save(any());
  }

  @Test
  void movePendingRefusesKeysOutsideThePendingPrefix() throws Exception {
    givenObjectExists();

    mvc.perform(
            post("/api/internal/documents/move-pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(moveBody("process/pi-1/u/sneaky.png")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value("pendingKey must live under the case initiator's pending/ prefix."));
    verify(s3, never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  void movePendingRefusesAnotherUsersPendingKey() throws Exception {
    givenObjectExists();

    mvc.perform(
            post("/api/internal/documents/move-pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(moveBody("pending/bart/u/id.png")))
        .andExpect(status().isBadRequest());

    verify(s3, never()).copyObject(any(CopyObjectRequest.class));
    verify(documents, never()).save(any());
  }

  @Test
  void movePendingOnAnUnknownCaseIsRefused() throws Exception {
    givenObjectExists();
    when(engine.getHistoricStartUserId(PI)).thenReturn(null);

    mvc.perform(
            post("/api/internal/documents/move-pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(moveBody("pending/lisa/u/id.png")))
        .andExpect(status().isBadRequest());

    verify(s3, never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  void movePendingWithAMissingObjectIs400() throws Exception {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).build());

    mvc.perform(
            post("/api/internal/documents/move-pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(moveBody("pending/lisa/u/id.png")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value("Pending object not found — already migrated or never uploaded?"));
  }

  @Test
  void movePendingCopiesThenDeletesAndRecordsTheInitiator() throws Exception {
    givenObjectExists();

    mvc.perform(
            post("/api/internal/documents/move-pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content(moveBody("pending/lisa/some-uuid/id.png")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attachmentId").isNotEmpty())
        .andExpect(jsonPath("$.key").doesNotExist());

    InOrder order = inOrder(s3);
    ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
    ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    order.verify(s3).copyObject(copy.capture());
    order.verify(s3).deleteObject(delete.capture());
    assertThat(copy.getValue().sourceKey()).isEqualTo("pending/lisa/some-uuid/id.png");
    assertThat(copy.getValue().destinationKey())
        .matches("process/" + PI + "/" + UUID_RE + "/id\\.png");
    assertThat(delete.getValue().key()).isEqualTo("pending/lisa/some-uuid/id.png");

    ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
    verify(documents).save(saved.capture());
    assertThat(saved.getValue().getUploaderUserId()).isEqualTo(INITIATOR);
  }

  /** Regression for 92cdb08: the byte cap applies to server-upload, not just stage. */
  @Test
  void serverUploadEnforcesTheSameByteCapAsStage() throws Exception {
    mvc.perform(
            post("/api/internal/documents/server-upload")
                .contentType(MediaType.APPLICATION_JSON)
                .content(uploadBody(1025)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("decoded size must be between 1 and 1024 bytes."));

    verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(documents, never()).save(any());
  }

  @Test
  void serverUploadStoresEngineGeneratedDocumentsWithoutAnUploader() throws Exception {
    mvc.perform(
            post("/api/internal/documents/server-upload")
                .contentType(MediaType.APPLICATION_JSON)
                .content(uploadBody(32)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attachmentId").isNotEmpty());

    ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3).putObject(put.capture(), any(RequestBody.class));
    assertThat(put.getValue().key()).matches("process/" + PI + "/" + UUID_RE + "/approval\\.pdf");

    ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
    verify(documents).save(saved.capture());
    assertThat(saved.getValue().getUploaderUserId()).isNull();
    assertThat(saved.getValue().getCategory()).isEqualTo("generated-approval-pdf");
  }
}
