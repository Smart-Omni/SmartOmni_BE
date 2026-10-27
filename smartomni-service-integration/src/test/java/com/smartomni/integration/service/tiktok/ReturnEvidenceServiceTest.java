package com.smartomni.integration.service.tiktok;

import com.smartomni.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReturnEvidenceService Unit Tests")
class ReturnEvidenceServiceTest {

    private ReturnEvidenceService evidenceService;

    @BeforeEach
    void setUp() {
        evidenceService = new ReturnEvidenceService();
    }

    @Test
    @DisplayName("uploadEvidence: tạo mock URL lưu trữ hợp lệ với tenantId và returnId")
    void testUploadEvidence_success() {
        String url = evidenceService.uploadEvidence(1L, "ret-123", "evidence_broken.jpg", new byte[]{1, 2, 3}, "image/jpeg");

        assertNotNull(url);
        assertTrue(url.startsWith("https://cdn.smartomni.com/returns/evidence/1/ret-123/"));
        assertTrue(url.endsWith("evidence_broken.jpg"));
    }

    @Test
    @DisplayName("uploadEvidence: ném BusinessException khi tenantId hoặc returnId rỗng")
    void testUploadEvidence_invalidArgs() {
        assertThrows(BusinessException.class, () ->
                evidenceService.uploadEvidence(null, "ret-123", "test.jpg", new byte[0], "image/jpeg")
        );
        assertThrows(BusinessException.class, () ->
                evidenceService.uploadEvidence(1L, "  ", "test.jpg", new byte[0], "image/jpeg")
        );
    }

    @Test
    @DisplayName("registerEvidenceUrl: chấp nhận URL hợp lệ và từ chối URL không hợp lệ")
    void testRegisterEvidenceUrl() {
        String validUrl = "https://cdn.example.com/images/damaged1.jpg";
        String registered = evidenceService.registerEvidenceUrl(10L, "ret-999", validUrl);
        assertEquals(validUrl, registered);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                evidenceService.registerEvidenceUrl(10L, "ret-999", "ftp://invalid-url.com")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("INVALID_URL_FORMAT", ex.getErrorCode());
    }

    @Test
    @DisplayName("normalizeEvidenceUrls: loại bỏ rỗng, trùng lặp, URL sai cú pháp và giới hạn 5 phần tử")
    void testNormalizeEvidenceUrls() {
        List<String> raw = List.of(
                "https://cdn.smartomni.com/img1.jpg",
                "  ",
                "https://cdn.smartomni.com/img2.jpg",
                "https://cdn.smartomni.com/img1.jpg", // duplicate
                "invalid-format",
                "https://cdn.smartomni.com/img3.jpg",
                "http://cdn.smartomni.com/img4.jpg",
                "https://cdn.smartomni.com/img5.jpg",
                "https://cdn.smartomni.com/img6.jpg"  // vượt quá max 5
        );

        List<String> clean = evidenceService.normalizeEvidenceUrls(raw);

        assertEquals(5, clean.size());
        assertEquals("https://cdn.smartomni.com/img1.jpg", clean.get(0));
        assertEquals("https://cdn.smartomni.com/img2.jpg", clean.get(1));
        assertEquals("https://cdn.smartomni.com/img3.jpg", clean.get(2));
        assertEquals("http://cdn.smartomni.com/img4.jpg", clean.get(3));
        assertEquals("https://cdn.smartomni.com/img5.jpg", clean.get(4));
    }

    @Test
    @DisplayName("prepareReviewPayload: đóng gói chuẩn payload cho Aftersales Review / Dispute API")
    void testPrepareReviewPayload() {
        Map<String, Object> payload = evidenceService.prepareReviewPayload(
                "ret-777",
                "REJECT",
                "Tem niêm phong đã bị rách trước khi trả",
                List.of("https://cdn.smartomni.com/ev1.jpg")
        );

        assertEquals("ret-777", payload.get("return_id"));
        assertEquals("REJECT", payload.get("decision"));
        assertEquals("Tem niêm phong đã bị rách trước khi trả", payload.get("reject_reason"));
        assertEquals(List.of("https://cdn.smartomni.com/ev1.jpg"), payload.get("evidence_urls"));
    }
}
