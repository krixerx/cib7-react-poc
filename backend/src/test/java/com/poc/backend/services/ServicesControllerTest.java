package com.poc.backend.services;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The anonymous service list, through the real filter chains. */
@SpringBootTest
@AutoConfigureMockMvc
class ServicesControllerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired MockMvc mvc;
  @MockitoBean S3Client s3Client;
  @MockitoBean S3Presigner s3Presigner;
  @MockitoBean EngineClient engine;

  @Test
  void listsActiveServicesWithoutTokenAndOnlyTheirPublicFields() throws Exception {
    when(engine.latestProcessDefinitions())
        .thenReturn(
            List.of(
                definition(
                    "{\"id\":\"vr:3:a\",\"key\":\"vr\",\"name\":\"Vehicle registration\","
                        + "\"version\":3,\"suspended\":false,\"resource\":\"vr.bpmn\","
                        + "\"deploymentId\":\"d1\"}"),
                definition(
                    "{\"id\":\"old:1:b\",\"key\":\"old\",\"name\":\"Old\",\"version\":1,"
                        + "\"suspended\":true}"),
                definition(
                    "{\"id\":\"br:1:c\",\"key\":\"br\",\"name\":\"Business registration\","
                        + "\"version\":1,\"suspended\":false}")));

    mvc.perform(get("/api/public/services"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].key").value("br"))
        .andExpect(jsonPath("$[1].id").value("vr:3:a"))
        .andExpect(jsonPath("$[1].name").value("Vehicle registration"))
        .andExpect(jsonPath("$[1].version").value(3))
        .andExpect(jsonPath("$[1].resource").doesNotExist())
        .andExpect(jsonPath("$[1].deploymentId").doesNotExist());
  }

  private static JsonNode definition(String json) {
    return JSON.readTree(json);
  }
}
