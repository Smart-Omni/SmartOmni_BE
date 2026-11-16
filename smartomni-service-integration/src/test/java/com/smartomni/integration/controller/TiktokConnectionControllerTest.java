package com.smartomni.integration.controller;

import com.smartomni.integration.dto.tiktok.TiktokAuthorizationStartDto;
import com.smartomni.integration.dto.tiktok.TiktokAuthorizationStartRequest;
import com.smartomni.integration.service.tiktok.TiktokConnectionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TiktokConnectionControllerTest {
    private final TiktokConnectionService service = mock(TiktokConnectionService.class);
    private final TiktokConnectionController controller = new TiktokConnectionController(service);

    @Test
    void acceptsQueryRedirect() {
        when(service.start("smartomni://oauth/tiktok")).thenReturn(
                new TiktokAuthorizationStartDto("https://simulator.test/consent", Instant.now()));
        assertTrue(controller.start("smartomni://oauth/tiktok", null).isSuccess());
        verify(service).start("smartomni://oauth/tiktok");
    }

    @Test
    void bodyRedirectTakesPrecedence() {
        controller.start("https://web.example/callback", new TiktokAuthorizationStartRequest("smartomni://oauth/tiktok"));
        verify(service).start("smartomni://oauth/tiktok");
    }
}
