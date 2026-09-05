package com.scheduler.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.controller.TaskController;
import com.scheduler.api.dto.CreateTaskRequest;
import com.scheduler.api.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TaskController.class)
@Import({ApiKeyAuthenticationFilter.class, RateLimitingFilter.class})
@ActiveProfiles("test")
class ApiKeyAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @MockBean
    private com.scheduler.shared.metrics.TaskMetrics taskMetrics;

    @MockBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("Request without X-API-Key header returns 401 Unauthorized with ApiError body")
    void requestWithoutApiKey_returns401Unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"DEMO\",\"payload\":\"{}\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.status").value(401));
    }

    @Test
    @DisplayName("Request with invalid X-API-Key header returns 401 Unauthorized")
    void requestWithInvalidApiKey_returns401Unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"DEMO\",\"payload\":\"{}\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.status").value(401));
    }

    @Test
    @DisplayName("Request with valid X-API-Key header succeeds")
    void requestWithValidApiKey_succeeds() throws Exception {
        var dummyTask = com.scheduler.shared.domain.Task.builder()
                .taskType("DEMO")
                .payload("{}")
                .build();
        when(taskService.createTask(any(CreateTaskRequest.class)))
                .thenReturn(new TaskService.CreateResult(dummyTask, true));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-API-Key", "test-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"DEMO\",\"payload\":\"{}\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Health endpoint is excluded from API Key requirement")
    void healthEndpoint_accessibleWithoutApiKey() throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/actuator/health");
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter("test-api-key", new ObjectMapper());
        org.junit.jupiter.api.Assertions.assertTrue(filter.shouldNotFilter(request));
    }
}
