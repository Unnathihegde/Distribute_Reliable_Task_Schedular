package com.scheduler.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.shared.domain.Priority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrometheusMetricsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("GET /actuator/prometheus returns custom metrics including tasks_submitted_total after task creation")
    void testPrometheusEndpointReturnsCustomMetrics() throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskType("DEMO");
        request.setPayload("{\"key\":\"value\"}");
        request.setPriority(Priority.HIGH);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        ResponseEntity<String> response = restTemplate.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("tasks_submitted_total")
                .contains("type=\"DEMO\"")
                .contains("priority=\"HIGH\"");
    }
}
