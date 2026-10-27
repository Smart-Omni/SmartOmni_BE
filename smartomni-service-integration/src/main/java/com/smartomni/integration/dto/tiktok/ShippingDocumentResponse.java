package com.smartomni.integration.dto.tiktok;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShippingDocumentResponse {

    @JsonProperty("doc_url")
    private String docUrl;

    @JsonProperty("file_url")
    private String fileUrl;

    @JsonProperty("document_type")
    private String documentType;

    @JsonProperty("tracking_number")
    private String trackingNumber;
}
