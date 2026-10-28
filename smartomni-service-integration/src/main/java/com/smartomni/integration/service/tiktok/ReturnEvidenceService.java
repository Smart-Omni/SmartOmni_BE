package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service hỗ trợ tiếp nhận, lưu trữ (mock/cloud) và chuẩn hóa danh sách evidence_urls (ảnh/video chứng từ)
 * để sẵn sàng đính kèm vào payload gửi sang TikTok Shop Aftersales Review / Dispute API (Bước 5.2).
 */
@Slf4j
@Service
public class ReturnEvidenceService {

    public static final int MAX_EVIDENCE_COUNT = 5;
    private static final String DEFAULT_STORAGE_BASE_URL = "https://cdn.smartomni.com/returns/evidence";

    /**
     * Nhận upload file bằng chứng (dưới dạng byte[] hoặc mock), tạo URL lưu trữ chuẩn hóa.
     */
    public String uploadEvidence(Long tenantId, String returnId, String fileName, byte[] content, String contentType) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required and must be positive", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (returnId == null || returnId.isBlank()) {
            throw new BusinessException("returnId is required", HttpStatus.BAD_REQUEST, "INVALID_RETURN_ID");
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = "evidence_" + System.currentTimeMillis() + ".jpg";
        }

        // Mock lưu trữ / upload lên cloud CDN/S3: sinh URL định danh duy nhất
        String sanitizedFileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String uniqueKey = UUID.randomUUID().toString().substring(0, 8);
        String mockStoredUrl = String.format("%s/%d/%s/%s_%s",
                DEFAULT_STORAGE_BASE_URL, tenantId, returnId.trim(), uniqueKey, sanitizedFileName);

        log.info("Đã lưu trữ file bằng chứng trả hàng thành công: tenantId={}, returnId={}, url={}",
                tenantId, returnId, mockStoredUrl);
        return mockStoredUrl;
    }

    /**
     * Hỗ trợ đăng ký mock URL trực tiếp.
     */
    public String registerEvidenceUrl(Long tenantId, String returnId, String directUrl) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required and must be positive", HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID");
        }
        if (returnId == null || returnId.isBlank()) {
            throw new BusinessException("returnId is required", HttpStatus.BAD_REQUEST, "INVALID_RETURN_ID");
        }
        if (directUrl == null || directUrl.isBlank()) {
            throw new BusinessException("Evidence URL must not be blank", HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE_URL");
        }
        validateUrl(directUrl.trim());
        return directUrl.trim();
    }

    /**
     * Chuẩn hóa danh sách evidence_urls:
     * - Loại bỏ null, blank, duplicate
     * - Validate định dạng URL (http/https)
     * - Giới hạn tối đa MAX_EVIDENCE_COUNT bằng chứng theo quy định của TikTok Aftersales
     */
    public List<String> normalizeEvidenceUrls(List<String> rawUrls) {
        if (rawUrls == null || rawUrls.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> normalized = rawUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(url -> !url.isBlank())
                .filter(this::isValidHttpUrl)
                .distinct()
                .limit(MAX_EVIDENCE_COUNT)
                .collect(Collectors.toList());

        return Collections.unmodifiableList(normalized);
    }

    /**
     * Chuẩn bị payload đính kèm bằng chứng gửi sang TikTok Aftersales Review / Dispute API.
     */
    public Map<String, Object> prepareReviewPayload(String returnId, String decision, String rejectReason, List<String> evidenceUrls) {
        List<String> cleanUrls = normalizeEvidenceUrls(evidenceUrls);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("return_id", returnId);
        payload.put("decision", decision);
        if (rejectReason != null && !rejectReason.isBlank()) {
            payload.put("reject_reason", rejectReason.trim());
        }
        payload.put("evidence_urls", cleanUrls);
        return payload;
    }

    private boolean isValidHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    private void validateUrl(String url) {
        if (!isValidHttpUrl(url)) {
            throw new BusinessException("Invalid URL format: " + url, HttpStatus.BAD_REQUEST, "INVALID_URL_FORMAT");
        }
    }
}
