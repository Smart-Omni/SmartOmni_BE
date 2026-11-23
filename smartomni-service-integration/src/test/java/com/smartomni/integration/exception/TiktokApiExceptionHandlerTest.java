package com.smartomni.integration.exception;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.integration.client.tiktok.TiktokApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TiktokApiExceptionHandlerTest {

    private final TiktokApiExceptionHandler handler = new TiktokApiExceptionHandler();

    @Test
    void transientFailureIs503() {
        assertMapped(new TiktokApiException("timeout", 0, null, null, true), HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_UNAVAILABLE");
        assertMapped(new TiktokApiException("server", 502, null, null, true), HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_UNAVAILABLE");
    }

    @Test
    void authorizationRefusalIs409Reconnect() {
        assertMapped(new TiktokApiException("unauthorized", 401, 105002, null, false), HttpStatus.CONFLICT, "TIKTOK_RECONNECT_REQUIRED");
        assertMapped(new TiktokApiException("forbidden", 403, 105005, null, false), HttpStatus.CONFLICT, "TIKTOK_RECONNECT_REQUIRED");
    }

    @Test
    void notFoundIs502() {
        assertMapped(new TiktokApiException("missing", 404, null, null, false), HttpStatus.BAD_GATEWAY, "TIKTOK_NOT_FOUND");
    }

    @Test
    void otherRejectionsAre422WithSummaryButNoPayload() {
        ResponseEntity<ApiResponse<Object>> response =
                handler.handle(new TiktokApiException("TikTok rejected SKU inventory update", 200, 12052700, "req-9", false));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("TIKTOK_REQUEST_REJECTED", response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("code=12052700"));
        assertTrue(response.getBody().getMessage().contains("requestId=req-9"));
        assertFalse(response.getBody().getMessage().contains("{"));
        assertMapped(new TiktokApiException("invalid_input", 400, 40006, null, false),
                HttpStatus.UNPROCESSABLE_ENTITY, "TIKTOK_REQUEST_REJECTED");
    }

    @Test
    void winsOverTheSharedCatchAllHandlerButLeavesOtherExceptionsToIt() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler(), handler).build();

        mvc.perform(get("/tiktok-fails")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TIKTOK_UNAVAILABLE"));
        mvc.perform(get("/other-fails")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    private void assertMapped(TiktokApiException ex, HttpStatus status, String code) {
        ResponseEntity<ApiResponse<Object>> response = handler.handle(ex);
        assertEquals(status, response.getStatusCode());
        assertEquals(code, response.getBody().getCode());
    }

    @RestController
    static class FailingController {
        @GetMapping("/tiktok-fails")
        String tiktok() {
            throw new TiktokApiException("down", 503, null, null, true);
        }

        @GetMapping("/other-fails")
        String other() {
            throw new IllegalStateException("boom");
        }
    }
}
