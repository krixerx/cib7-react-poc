package com.poc.backend.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.security.CaseAccessService;
import com.poc.backend.storage.S3Properties;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
 * Web-slice tests for the documents API: request validation, the 404-not-403 case-access contract
 * (no existence probing), object-key shaping (pending/ vs process/, filename sanitization), the
 * byte caps on both base64 endpoints, and the S3 call choreography.
 *
 * <p>Security filters are off ({@code addFilters = false}); the JWT identity is planted straight
 * into {@link SecurityContextHolder} (MockMvc dispatches on the calling thread), because {@code
 * currentUserId()} reads the holder directly and the {@code jwt()} request post-processor only
 * takes effect through the security filter chain we disabled.
 *
 * <p>The {@code app.s3.*} test properties matter: {@link S3Properties} is
 * {@code @ConfigurationProperties}-bound, so the binder would otherwise overwrite any values set on
 * the {@code Props} bean with the real application.yaml's 10 MB cap.
 */
@WebMvcTest(controllers = DocumentsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(DocumentsControllerWebTest.Props.class)
@TestPropertySource(properties = {"app.s3.bucket=test-bucket", "app.s3.max-bytes=1024"})
class DocumentsControllerWebTest {

  private static final String PI = "pi-42";
  private static final String USER = "lisa";
  private static final long MAX_BYTES = 1024;
  private static final String UUID_RE = "[0-9a-f-]{36}";

  /** Registers + binds S3Properties from the @TestPropertySource values above. */
  @TestConfiguration
  @EnableConfigurationProperties(S3Properties.class)
  static class Props {}

  @Autowired MockMvc mvc;
  @MockitoBean S3Client s3;
  @MockitoBean S3Presigner presigner;
  @MockitoBean DocumentRepository documents;
  @MockitoBean CaseAccessService caseAccess;

  @BeforeEach
  void authenticateAsLisa() {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .claim("preferred_username", USER)
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  /** Drops the planted JWT — the request then runs like the internal (token-filter) chain. */
  private static void logout() {
    SecurityContextHolder.clearContext();
  }

  @BeforeEach
  void stubCollaborators() throws Exception {
    PresignedPutObjectRequest put = mock(PresignedPutObjectRequest.class);
    when(put.url()).thenReturn(URI.create("http://localhost:9000/signed-put").toURL());
    when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(put);

    PresignedGetObjectRequest got = mock(PresignedGetObjectRequest.class);
    when(got.url()).thenReturn(URI.create("http://localhost:9000/signed-get").toURL());
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(got);

    when(documents.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  /** Object exists in storage from the controller's headObject point of view. */
  private void givenObjectExists() {
    when(s3.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().build());
  }

  private static String base64Of(int byteCount) {
    return Base64.getEncoder().encodeToString(new byte[byteCount]);
  }

  // =====================================================================
  // POST /api/documents/upload-url
  // =====================================================================

  @Nested
  class UploadUrl {

    private MockHttpServletRequestBuilder request(String body) {
      return post("/api/documents/upload-url")
          .contentType(MediaType.APPLICATION_JSON)
          .content(body);
    }

    @Test
    void rejectsDisallowedContentType() throws Exception {
      mvc.perform(
              request("{\"filename\":\"cv.exe\",\"contentType\":\"application/exe\",\"size\":10}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void rejectsSizeOutsideTheCap() throws Exception {
      mvc.perform(
              request("{\"filename\":\"cv.pdf\",\"contentType\":\"application/pdf\",\"size\":0}"))
          .andExpect(status().isBadRequest());
      mvc.perform(
              request(
                  "{\"filename\":\"cv.pdf\",\"contentType\":\"application/pdf\",\"size\":"
                      + (MAX_BYTES + 1)
                      + "}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("size must be between 1 and 1024 bytes."));
    }

    @Test
    void unauthenticatedCallerIs401() throws Exception {
      logout();
      mvc.perform(
              request("{\"filename\":\"cv.pdf\",\"contentType\":\"application/pdf\",\"size\":10}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("no_user"));
    }

    @Test
    void defaultScopeStagesUnderPendingWithTheCallersUserId() throws Exception {
      mvc.perform(
              request("{\"filename\":\"cv.pdf\",\"contentType\":\"application/pdf\",\"size\":10}"))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.key")
                  .value(
                      org.hamcrest.Matchers.matchesPattern(
                          "pending/" + USER + "/" + UUID_RE + "/cv\\.pdf")))
          .andExpect(jsonPath("$.url").value("http://localhost:9000/signed-put"))
          .andExpect(jsonPath("$.headers['Content-Type']").value("application/pdf"))
          .andExpect(jsonPath("$.expiresIn").value(300));

      // The presigned PUT must anchor the declared type and size — that IS
      // the enforcement (the storage rejects a mismatching upload).
      ArgumentCaptor<PutObjectPresignRequest> presign =
          ArgumentCaptor.forClass(PutObjectPresignRequest.class);
      verify(presigner).presignPutObject(presign.capture());
      PutObjectRequest anchored = presign.getValue().putObjectRequest();
      assertThat(anchored.contentType()).isEqualTo("application/pdf");
      assertThat(anchored.contentLength()).isEqualTo(10L);
      assertThat(anchored.bucket()).isEqualTo("test-bucket");
    }

    @Test
    void processScopeWithoutScopeIdIs400() throws Exception {
      mvc.perform(
              request(
                  "{\"filename\":\"cv.pdf\",\"contentType\":\"application/pdf\",\"size\":10,"
                      + "\"scope\":\"process\"}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    void processScopeOnAForeignCaseIs404NotForbidden() throws Exception {
      when(caseAccess.canAccessCase(PI)).thenReturn(false);

      mvc.perform(
              request(
                  "{\"filename\":\"cv.pdf\",\"contentType\":\"application/pdf\",\"size\":10,"
                      + "\"scope\":\"process\",\"scopeId\":\""
                      + PI
                      + "\"}"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("not_found"))
          .andExpect(jsonPath("$.message").value("No such case."));
    }

    @Test
    void processScopeOnAnAccessibleCaseKeysUnderTheProcessPrefix() throws Exception {
      when(caseAccess.canAccessCase(PI)).thenReturn(true);

      mvc.perform(
              request(
                  "{\"filename\":\"cv.pdf\",\"contentType\":\"application/pdf\",\"size\":10,"
                      + "\"scope\":\"process\",\"scopeId\":\""
                      + PI
                      + "\"}"))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.key")
                  .value(
                      org.hamcrest.Matchers.matchesPattern(
                          "process/" + PI + "/" + UUID_RE + "/cv\\.pdf")));
    }

    @Test
    void sanitizesPathTraversalAndOddCharactersOutOfTheFilename() throws Exception {
      mvc.perform(
              request(
                  "{\"filename\":\"../../etc passwd.pdf\",\"contentType\":\"application/pdf\","
                      + "\"size\":10}"))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.key")
                  .value(
                      org.hamcrest.Matchers.matchesPattern(
                          "pending/" + USER + "/" + UUID_RE + "/\\.\\._\\.\\._etc_passwd\\.pdf")));
    }
  }

  // =====================================================================
  // POST /api/documents/stage  (MCP server-side staging)
  // =====================================================================

  @Nested
  class Stage {

    private MockHttpServletRequestBuilder request(String body) {
      return post("/api/documents/stage").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String body(String category, String base64) {
      return "{\"filename\":\"id.png\",\"contentType\":\"image/png\",\"category\":\""
          + category
          + "\",\"base64\":\""
          + base64
          + "\"}";
    }

    @Test
    void rejectsUnknownCategory() throws Exception {
      mvc.perform(request(body("malware-dropper", base64Of(8))))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.message")
                  .value(org.hamcrest.Matchers.startsWith("category must be one of")));
    }

    @Test
    void rejectsUndecodableBase64() throws Exception {
      mvc.perform(request(body("applicant-id-document", "%%%not-base64%%%")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("base64 was not decodable."));
    }

    @Test
    void enforcesTheDecodedByteCapAndRejectsEmptyPayloads() throws Exception {
      mvc.perform(request(body("applicant-id-document", base64Of((int) MAX_BYTES + 1))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("decoded size must be between 1 and 1024 bytes."));
      mvc.perform(request(body("applicant-id-document", ""))).andExpect(status().isBadRequest());
      verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void unauthenticatedCallerIs401() throws Exception {
      logout();
      mvc.perform(request(body("applicant-id-document", base64Of(8))))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("no_user"));
    }

    @Test
    void decodesAndStoresUnderTheCallersPendingPrefix() throws Exception {
      mvc.perform(request(body("applicant-id-document", base64Of(16))))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.pendingKey")
                  .value(
                      org.hamcrest.Matchers.matchesPattern(
                          "pending/" + USER + "/" + UUID_RE + "/id\\.png")))
          .andExpect(jsonPath("$.filename").value("id.png"))
          .andExpect(jsonPath("$.contentType").value("image/png"));

      ArgumentCaptor<PutObjectRequest> putReq = ArgumentCaptor.forClass(PutObjectRequest.class);
      verify(s3).putObject(putReq.capture(), any(RequestBody.class));
      assertThat(putReq.getValue().contentLength()).isEqualTo(16L);
      assertThat(putReq.getValue().contentType()).isEqualTo("image/png");
    }
  }

  // =====================================================================
  // POST /api/documents/{pi}/attachments + GET /api/documents/{pi}
  // =====================================================================

  @Nested
  class RegisterAndList {

    @Test
    void registerOnAForeignCaseIs404AndPersistsNothing() throws Exception {
      when(caseAccess.canAccessCase(PI)).thenReturn(false);

      mvc.perform(
              post("/api/documents/{pi}/attachments", PI)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"key\":\"pending/lisa/u/id.png\",\"filename\":\"id.png\","
                          + "\"contentType\":\"image/png\",\"category\":\"applicant-id-document\"}"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("not_found"));

      verify(documents, never()).save(any());
    }

    @Test
    void registerWithoutACompletedUploadIs400() throws Exception {
      when(caseAccess.canAccessCase(PI)).thenReturn(true);
      when(s3.headObject(any(HeadObjectRequest.class)))
          .thenThrow(NoSuchKeyException.builder().build());

      mvc.perform(
              post("/api/documents/{pi}/attachments", PI)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"key\":\"pending/lisa/u/id.png\",\"filename\":\"id.png\","
                          + "\"contentType\":\"image/png\",\"category\":\"applicant-id-document\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.message").value("Object not found in storage. Did the upload complete?"));
    }

    @Test
    void registerRecordsTheCallerAsUploader() throws Exception {
      when(caseAccess.canAccessCase(PI)).thenReturn(true);
      givenObjectExists();

      mvc.perform(
              post("/api/documents/{pi}/attachments", PI)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"key\":\"pending/lisa/u/id.png\",\"filename\":\"id.png\","
                          + "\"contentType\":\"image/png\",\"category\":\"applicant-id-document\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.attachmentId").isNotEmpty())
          .andExpect(jsonPath("$.key").value("pending/lisa/u/id.png"));

      ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
      verify(documents).save(saved.capture());
      assertThat(saved.getValue().getUploaderUserId()).isEqualTo(USER);
      assertThat(saved.getValue().getProcessInstanceId()).isEqualTo(PI);
    }

    @Test
    void listOnAForeignCaseIs404() throws Exception {
      when(caseAccess.canAccessCase(PI)).thenReturn(false);

      mvc.perform(get("/api/documents/{pi}", PI))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void listMapsDocumentRowsToEntries() throws Exception {
      when(caseAccess.canAccessCase(PI)).thenReturn(true);
      Document doc =
          new Document(
              PI, "applicant-id-document", "id.png", "image/png", "process/pi-42/u/id.png", USER);
      when(documents.findByProcessInstanceIdOrderByCreatedAtAsc(PI)).thenReturn(List.of(doc));

      mvc.perform(get("/api/documents/{pi}", PI))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].id").value(doc.getId()))
          .andExpect(jsonPath("$[0].category").value("applicant-id-document"))
          .andExpect(jsonPath("$[0].uploaderUserId").value(USER))
          .andExpect(jsonPath("$[0].key").value("process/pi-42/u/id.png"))
          .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
    }
  }

  // =====================================================================
  // GET /api/documents/attachments/{aid}/download-url
  // =====================================================================

  @Nested
  class DownloadUrl {

    @Test
    void unknownAttachmentIdIs404() throws Exception {
      when(documents.findById("nope")).thenReturn(Optional.empty());

      mvc.perform(get("/api/documents/attachments/{aid}/download-url", "nope"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("not_found"))
          .andExpect(jsonPath("$.message").value("No such attachment."));
    }

    @Test
    void attachmentOnAForeignCaseIs404WithTheIdenticalBody() throws Exception {
      Document doc = new Document(PI, "applicant-id-document", "id.png", "image/png", "k", USER);
      when(documents.findById(doc.getId())).thenReturn(Optional.of(doc));
      when(caseAccess.canAccessCase(PI)).thenReturn(false);

      // Indistinguishable from the unknown-id answer — no existence probing.
      mvc.perform(get("/api/documents/attachments/{aid}/download-url", doc.getId()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("not_found"))
          .andExpect(jsonPath("$.message").value("No such attachment."));
    }

    @Test
    void mintsAShortLivedGetUrlWithASanitizedDispositionFilename() throws Exception {
      Document doc =
          new Document(
              PI,
              "applicant-id-document",
              "naughty file.png",
              "image/png",
              "process/pi-42/u/naughty_file.png",
              USER);
      when(documents.findById(doc.getId())).thenReturn(Optional.of(doc));
      when(caseAccess.canAccessCase(PI)).thenReturn(true);

      mvc.perform(get("/api/documents/attachments/{aid}/download-url", doc.getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.url").value("http://localhost:9000/signed-get"))
          .andExpect(jsonPath("$.expiresIn").value(60));

      ArgumentCaptor<GetObjectPresignRequest> presign =
          ArgumentCaptor.forClass(GetObjectPresignRequest.class);
      verify(presigner).presignGetObject(presign.capture());
      assertThat(presign.getValue().getObjectRequest().responseContentDisposition())
          .isEqualTo("attachment; filename=\"naughty_file.png\"");
      assertThat(presign.getValue().getObjectRequest().key())
          .isEqualTo("process/pi-42/u/naughty_file.png");
    }
  }

  // =====================================================================
  // Internal endpoints: /move-pending + /server-upload
  // =====================================================================

  @Nested
  class InternalEndpoints {

    @BeforeEach
    void runOnTheInternalChain() {
      // These endpoints are called by the engine with X-Internal-Token,
      // not a JWT — drop the planted user.
      logout();
    }

    private String moveBody(String pendingKey) {
      return "{\"pendingKey\":\""
          + pendingKey
          + "\",\"processInstanceId\":\""
          + PI
          + "\","
          + "\"filename\":\"id.png\",\"contentType\":\"image/png\","
          + "\"category\":\"applicant-id-document\"}";
    }

    @Test
    void movePendingRefusesKeysOutsideThePendingPrefix() throws Exception {
      mvc.perform(
              post("/api/documents/move-pending")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(moveBody("process/pi-1/u/sneaky.png")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("pendingKey must live under pending/"));
    }

    @Test
    void movePendingWithAMissingObjectIs400() throws Exception {
      // The other objectExists branch: a plain S3Exception with status 404.
      when(s3.headObject(any(HeadObjectRequest.class)))
          .thenThrow(S3Exception.builder().statusCode(404).build());

      mvc.perform(
              post("/api/documents/move-pending")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(moveBody("pending/lisa/u/id.png")))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.message")
                  .value("Pending object not found — already migrated or never uploaded?"));
    }

    @Test
    void movePendingCopiesThenDeletesAndKeepsTheUploaderFromTheKey() throws Exception {
      givenObjectExists();

      mvc.perform(
              post("/api/documents/move-pending")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(moveBody("pending/lisa/some-uuid/id.png")))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.key")
                  .value(
                      org.hamcrest.Matchers.matchesPattern(
                          "process/" + PI + "/" + UUID_RE + "/id\\.png")));

      InOrder order = inOrder(s3);
      ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
      ArgumentCaptor<DeleteObjectRequest> delete =
          ArgumentCaptor.forClass(DeleteObjectRequest.class);
      order.verify(s3).copyObject(copy.capture());
      order.verify(s3).deleteObject(delete.capture());
      assertThat(copy.getValue().sourceKey()).isEqualTo("pending/lisa/some-uuid/id.png");
      assertThat(copy.getValue().destinationKey()).startsWith("process/" + PI + "/");
      assertThat(delete.getValue().key()).isEqualTo("pending/lisa/some-uuid/id.png");

      ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
      verify(documents).save(saved.capture());
      assertThat(saved.getValue().getUploaderUserId()).isEqualTo("lisa");
    }

    /** Regression for 92cdb08: the byte cap applies to /server-upload, not just /stage. */
    @Test
    void serverUploadEnforcesTheSameByteCapAsStage() throws Exception {
      mvc.perform(
              post("/api/documents/server-upload")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"processInstanceId\":\""
                          + PI
                          + "\",\"filename\":\"approval.pdf\","
                          + "\"contentType\":\"application/pdf\","
                          + "\"category\":\"generated-approval-pdf\","
                          + "\"base64\":\""
                          + base64Of((int) MAX_BYTES + 1)
                          + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("decoded size must be between 1 and 1024 bytes."));

      verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
      verify(documents, never()).save(any());
    }

    @Test
    void serverUploadStoresEngineGeneratedDocumentsWithoutAnUploader() throws Exception {
      mvc.perform(
              post("/api/documents/server-upload")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"processInstanceId\":\""
                          + PI
                          + "\",\"filename\":\"approval.pdf\","
                          + "\"contentType\":\"application/pdf\","
                          + "\"category\":\"generated-approval-pdf\","
                          + "\"base64\":\""
                          + base64Of(32)
                          + "\"}"))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.key")
                  .value(
                      org.hamcrest.Matchers.matchesPattern(
                          "process/" + PI + "/" + UUID_RE + "/approval\\.pdf")));

      ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
      verify(documents).save(saved.capture());
      assertThat(saved.getValue().getUploaderUserId()).isNull();
      assertThat(saved.getValue().getCategory()).isEqualTo("generated-approval-pdf");
    }

    @Test
    void caseAccessIsNeverConsultedOnTheInternalChain() throws Exception {
      givenObjectExists();

      mvc.perform(
              post("/api/documents/move-pending")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(moveBody("pending/lisa/u/id.png")))
          .andExpect(status().isOk());

      // Authorization for these endpoints is the X-Internal-Token filter
      // (tested separately); the per-case JWT check must not interfere.
      verify(caseAccess, never()).canAccessCase(anyString());
    }
  }
}
