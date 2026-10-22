package com.smartomni.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubdomainCheckResponse {
    private String subdomain;
    private boolean available;
    private String message;
}
