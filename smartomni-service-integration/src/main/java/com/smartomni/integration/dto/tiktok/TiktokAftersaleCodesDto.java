package com.smartomni.integration.dto.tiktok;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * D9 catalog of TikTok Shop aftersale reason codes, reject reasons, and status lifecycles.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TiktokAftersaleCodesDto {

    private List<ReasonCodeItem> buyerCancelReasons;
    private List<ReasonCodeItem> sellerCancelReasons;
    private List<ReasonCodeItem> returnRequestReasons;
    private List<ReasonCodeItem> rejectReasons;
    private List<StatusCodeItem> returnStatuses;
    private List<StatusCodeItem> cancelStatuses;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasonCodeItem {
        private String name;
        private String text;
        private String textVi;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusCodeItem {
        private String code;
        private String labelVi;
        private boolean terminal;
    }
}
