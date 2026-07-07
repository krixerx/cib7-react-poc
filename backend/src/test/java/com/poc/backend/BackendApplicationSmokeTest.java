package com.poc.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.security.CaseAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Boots the full application context with the dev-default properties from application.yaml.
 *
 * <p>The two AWS SDK beans from S3Config are replaced with mocks so {@link
 * com.poc.backend.storage.BucketBootstrap} (which runs on ApplicationReadyEvent) no-ops instead of
 * dialing RustFS. The EmbeddingModel is mocked for the same reason: the real OpenAiEmbeddingModel
 * points at the TEI embeddings container, which isn't running here — the bean itself starts
 * offline, but mocking keeps any embedding call from dialing out. Nothing else needs the network at
 * startup: the OAuth2 client registration only declares a token-uri (no OIDC discovery), the
 * resource-server JwtDecoder is built lazily from the configured jwk-set-uri, and EngineClient is
 * just a RestClient wrapper.
 */
@SpringBootTest
class BackendApplicationSmokeTest {

  @MockitoBean S3Client s3Client;
  @MockitoBean S3Presigner s3Presigner;
  @MockitoBean EmbeddingModel embeddingModel;

  @Test
  void contextLoads(ApplicationContext context) {
    assertThat(context.getBean(EngineClient.class)).isNotNull();
    assertThat(context.getBean(CaseAccessService.class)).isNotNull();
    assertThat(context.getBean(VectorStore.class)).isNotNull();
  }
}
