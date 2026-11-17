package com.smartomni.order.service.loyalty;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.order.dto.loyalty.IntegrationIssueVoucherRequest;
import com.smartomni.order.dto.loyalty.IntegrationIssueVoucherResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Internal HTTP client calling {@code smartomni-service-integration} to provision/verify
 * a loyalty reward voucher on TikTok Shop or Shopee.
 */
@Slf4j
@Component
public class IntegrationVoucherClient {

    private final JwtTokenProvider jwtTokenProvider;
    private final String integrationBaseUrl;
    private final RestTemplate restTemplate;

    public IntegrationVoucherClient(
            JwtTokenProvider jwtTokenProvider,
            @Value("${smartomni.services.integration-url:http://localhost:8086}") String integrationBaseUrl) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.integrationBaseUrl = integrationBaseUrl.replaceAll("/+$", "");
        this.restTemplate = new RestTemplate();
    }

    public IntegrationIssueVoucherResponse issueVoucher(Long tenantId, IntegrationIssueVoucherRequest request) {
        String token = jwtTokenProvider.generateToken(0L, "INTERNAL_SERVICE", tenantId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        HttpEntity<IntegrationIssueVoucherRequest> entity = new HttpEntity<>(request, headers);
        String url = integrationBaseUrl + "/internal/integrations/vouchers/issue";

        try {
            ResponseEntity<ApiResponse<IntegrationIssueVoucherResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<>() {
                    });
            ApiResponse<IntegrationIssueVoucherResponse> body = response.getBody();
            if (body != null && body.getData() != null) {
                return body.getData();
            }
            return IntegrationIssueVoucherResponse.builder()
                    .outcome("PENDING")
                    .failureReason("Empty response from integration service")
                    .build();
        } catch (Exception ex) {
            log.warn("Integration service voucher call failed for tenantId={}, requestKey={}: {}",
                    tenantId, request.getRequestKey(), ex.getMessage());
            return IntegrationIssueVoucherResponse.builder()
                    .outcome("PENDING")
                    .failureReason("Integration service unavailable: " + ex.getMessage())
                    .build();
        }
    }
}
