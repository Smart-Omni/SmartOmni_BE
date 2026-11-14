package com.smartomni.auth.service;

import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.CreateStaffInvitationRequest;
import com.smartomni.auth.dto.StaffInvitationResponse;

import java.util.List;

/**
 * Service quản lý lời mời nhân viên gia nhập cửa hàng (Staff Onboarding via Invitation).
 */
public interface StaffInvitationService {

    StaffInvitationResponse createInvitation(Long inviterUserId, Long tenantId, CreateStaffInvitationRequest request);

    List<StaffInvitationResponse> listTenantInvitations(Long tenantId);

    void cancelInvitation(Long tenantId, Long invitationId);

    List<StaffInvitationResponse> listMyInvitations(Long userId);

    AuthResponse acceptInvitation(Long userId, Long invitationId);

    StaffInvitationResponse declineInvitation(Long userId, Long invitationId);
}
