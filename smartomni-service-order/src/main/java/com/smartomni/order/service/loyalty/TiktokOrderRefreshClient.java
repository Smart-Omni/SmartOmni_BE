package com.smartomni.order.service.loyalty;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Asks the integration service to read one order from TikTok Shop and feed it through the normal order ingestion,
 * for a loyalty claim on an order that is not (yet) synced. Ingestion is asynchronous (RabbitMQ), so the caller still
 * has to wait for the local row.
 */
@Slf4j
@Component
public class TiktokOrderRefreshClient {

    public enum Outcome { FOUND, NOT_FOUND, NOT_CONNECTED, UNAVAILABLE }

    public record Result(Outcome outcome, String orderStatus) {
        public boolean completed() {
            return outcome == Outcome.FOUND && "COMPLETED".equalsIgnoreCase(orderStatus);
        }
    }

    private final JwtTokenProvider jwtTokenProvider;
    private final String integrationBaseUrl;
    private final RestTemplate restTemplate;

    public TiktokOrderRefreshClient(
            JwtTokenProvider jwtTokenProvider,
            @Value("${smartomni.services.integration-url:http://localhost:8086}") String integrationBaseUrl) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.integrationBaseUrl = integrationBaseUrl.replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        this.restTemplate = new RestTemplate(factory);
    }

    public Result refresh(Long tenantId, String platformOrderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtTokenProvider.generateToken(0L, "INTERNAL_SERVICE", tenantId));
        try {
            ResponseEntity<ApiResponse<Map<String, String>>> response = restTemplate.exchange(
                    integrationBaseUrl + "/internal/integrations/orders/tiktok/refresh",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("platformOrderId", platformOrderId), headers),
                    new ParameterizedTypeReference<>() {
                    });
            Map<String, String> data = response.getBody() == null ? null : response.getBody().getData();
            if (data == null || data.get("outcome") == null) {
                return new Result(Outcome.UNAVAILABLE, null);
            }
            return new Result(Outcome.valueOf(data.get("outcome")), data.get("orderStatus"));
        } catch (Exception ex) {
            log.warn("TikTok order refresh failed for tenantId={}, order={}: {}", tenantId, platformOrderId, ex.getMessage());
            return new Result(Outcome.UNAVAILABLE, null);
        }
    }
}
