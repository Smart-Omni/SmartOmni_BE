package com.smartomni.auth.controller;

import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.CreateStaffInvitationRequest;
import com.smartomni.auth.dto.StaffInvitationResponse;
import com.smartomni.auth.service.StaffInvitationService;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller cho các API mời nhân viên gia nhập cửa hàng (Staff Onboarding via Invitation).
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class StaffInvitationController {

    private final StaffInvitationService staffInvitationService;

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StaffInvitationResponse> createInvitation(@Valid @RequestBody CreateStaffInvitationRequest request) {
        Long inviterUserId = resolveCurrentUserId();
        Long tenantId = TenantContext.getTenantId();
        StaffInvitationResponse response = staffInvitationService.createInvitation(inviterUserId, tenantId, request);
        return ApiResponse.success("Gửi lời mời nhân viên thành công", response);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @GetMapping("/invitations")
    public ApiResponse<List<StaffInvitationResponse>> listTenantInvitations() {
        Long tenantId = TenantContext.getTenantId();
        return ApiResponse.success("Lấy danh sách lời mời thành công", staffInvitationService.listTenantInvitations(tenantId));
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @DeleteMapping("/invitations/{id}")
    public ApiResponse<Void> cancelInvitation(@PathVariable Long id) {
        Long tenantId = TenantContext.getTenantId();
        staffInvitationService.cancelInvitation(tenantId, id);
        return ApiResponse.success("Hủy lời mời thành công", null);
    }

    @GetMapping("/me/invitations")
    public ApiResponse<List<StaffInvitationResponse>> listMyInvitations() {
        Long userId = resolveCurrentUserId();
        return ApiResponse.success("Lấy danh sách lời mời cá nhân thành công", staffInvitationService.listMyInvitations(userId));
    }

    @PostMapping("/me/invitations/{id}/accept")
    public ApiResponse<AuthResponse> acceptInvitation(@PathVariable Long id) {
        Long userId = resolveCurrentUserId();
        AuthResponse response = staffInvitationService.acceptInvitation(userId, id);
        return ApiResponse.success("Chấp nhận lời mời thành công", response);
    }

    @PostMapping("/me/invitations/{id}/decline")
    public ApiResponse<StaffInvitationResponse> declineInvitation(@PathVariable Long id) {
        Long userId = resolveCurrentUserId();
        StaffInvitationResponse response = staffInvitationService.declineInvitation(userId, id);
        return ApiResponse.success("Từ chối lời mời thành công", response);
    }

    private Long resolveCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null) {
            try {
                return Long.valueOf(authentication.getPrincipal().toString());
            } catch (NumberFormatException ignored) {
            }
        }
        Long contextUserId = TenantContext.getCurrentUserId();
        if (contextUserId != null) {
            return contextUserId;
        }
        throw new BusinessException("Không thể xác định danh tính người dùng hiện tại", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }
}
