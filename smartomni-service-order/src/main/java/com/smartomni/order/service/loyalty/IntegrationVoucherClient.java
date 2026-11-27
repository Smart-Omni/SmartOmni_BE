package com.smartomni.order.service.loyalty;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.order.dto.loyalty.IntegrationIssueVoucherRequest;
import com.smartomni.order.dto.loyalty.IntegrationIssueVoucherResponse;
import com.smartomni.order.dto.loyalty.TiktokVoucherBatchDto;
import org.springframework.http.HttpStatus;
import java.util.List;
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

    /**
     * Code batches of a TikTok connection, read live from TikTok through the integration service.
     *
     * @throws BusinessException 503 {@code TIKTOK_BATCHES_UNAVAILABLE} when they cannot be read
     */
    public List<TiktokVoucherBatchDto> listTiktokBatches(Long tenantId, Long connectionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateToken(0L, "INTERNAL_SERVICE", tenantId));
        String url = integrationBaseUrl + "/internal/integrations/vouchers/tiktok-batches?connectionId=" + connectionId;
        try {
            ResponseEntity<ApiResponse<List<TiktokVoucherBatchDto>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                    });
            ApiResponse<List<TiktokVoucherBatchDto>> body = response.getBody();
            return body != null && body.getData() != null ? body.getData() : List.of();
        } catch (Exception ex) {
            log.warn("Could not read TikTok code batches for tenantId={}, connectionId={}: {}",
                    tenantId, connectionId, ex.getMessage());
            throw new BusinessException("Could not read the TikTok code batches to check the tiers. Try again.",
                    HttpStatus.SERVICE_UNAVAILABLE, "TIKTOK_BATCHES_UNAVAILABLE");
        }
    }
}
