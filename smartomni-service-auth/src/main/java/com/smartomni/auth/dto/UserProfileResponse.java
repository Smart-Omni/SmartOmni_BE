package com.smartomni.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private Long id;

    private String email;

    @JsonProperty("full_name")
    private String fullName;

    private String phone;

    private String avatar;

    private String role;

    private String status;

    @JsonProperty("tenant_id")
    private Long tenantId;

    private String subdomain;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;
}
