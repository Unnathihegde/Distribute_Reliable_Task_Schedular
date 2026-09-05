package com.scheduler.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnconfiguredApiKeyStartupTest {

    @Test
    @DisplayName("STARTUP FAIL-FAST PROOF: Filter initialization fails with IllegalStateException if API_KEY is null or blank")
    void unconfiguredApiKey_causesStartupFailure() {
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter("", new ObjectMapper());

        IllegalStateException ex = assertThrows(IllegalStateException.class, filter::validateConfiguration);

        assertTrue(ex.getMessage().contains("API_KEY environment variable"),
                "Startup exception message must state that API_KEY is required");
    }
}
