package com.smartomni.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO for warehouse staff to submit physical return inspection results (Luồng 9 bước 9.5a, WF-05, DS-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReturnInspectionRequest {

    @NotNull(message = "inspection result is required")
    @JsonProperty("result")
    private InspectionResult result;

    @JsonProperty("video_evidence_url")
    private String videoEvidenceUrl;

    @JsonProperty("note")
    private String note;

    @Builder.Default
    @JsonProperty("items")
    private List<InspectedItemDto> items = new ArrayList<>();

    public enum InspectionResult {
        MATCHED,
        DAMAGED,
        SWAPPED,
        MISSING_ITEMS
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InspectedItemDto {
        @JsonProperty("sku_id")
        private Long skuId;

        @JsonProperty("inspected_quantity")
        private Integer inspectedQuantity;
    }
}
