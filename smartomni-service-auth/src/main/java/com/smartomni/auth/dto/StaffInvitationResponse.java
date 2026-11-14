package com.smartomni.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffInvitationResponse {

    private Long id;
    private Long tenantId;
    private String companyName;
    private String subdomain;
    private String invitedEmail;
    private Long invitedUserId;
    private String role;
    private String status;
    private Long invitedBy;
    private Instant expiresAt;
    private Instant respondedAt;
    private Instant createdAt;
}
