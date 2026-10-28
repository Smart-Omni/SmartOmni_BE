package com.smartomni.auth.controller;

import com.smartomni.auth.dto.AdminUpdateUserRequest;
import com.smartomni.auth.dto.AssignRolesRequest;
import com.smartomni.auth.dto.CreateUserRequest;
import com.smartomni.auth.dto.PageResponse;
import com.smartomni.auth.dto.UpdateProfileRequest;
import com.smartomni.auth.dto.UpdateUserStatusRequest;
import com.smartomni.auth.dto.UserProfileResponse;
import com.smartomni.auth.service.UserService;
import com.smartomni.common.dto.ApiResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller cho các API liên quan đến thông tin cá nhân của người dùng (Profile cá nhân).
 */
@RestController
@RequestMapping({"/api/v1/users", "/api/users"})
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * GET /api/v1/users/me hoặc /api/users/me
     * Lấy thông tin chi tiết của người dùng đang đăng nhập từ SecurityContext / JWT Principal.
     */
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getCurrentUser() {
        Long userId = resolveCurrentUserId();
        UserProfileResponse profile = userService.getCurrentUserProfile(userId);
        return ApiResponse.success("Lấy thông tin người dùng thành công", profile);
    }

    /**
     * PUT /api/v1/users/me hoặc /api/users/me
     * Cập nhật thông tin cá nhân của người dùng hiện tại (fullName, phone, avatar).
     */
    @PutMapping("/me")
    public ApiResponse<UserProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        Long userId = resolveCurrentUserId();
        UserProfileResponse updatedProfile = userService.updateCurrentUserProfile(userId, request);
        return ApiResponse.success("Cập nhật thông tin cá nhân thành công", updatedProfile);
    }

    /**
     * PATCH /api/v1/users/me hoặc /api/users/me
     * Cập nhật một phần thông tin cá nhân của người dùng hiện tại.
     */
    @PatchMapping("/me")
    public ApiResponse<UserProfileResponse> patchProfile(@RequestBody UpdateProfileRequest request) {
        Long userId = resolveCurrentUserId();
        UserProfileResponse updatedProfile = userService.updateCurrentUserProfile(userId, request);
        return ApiResponse.success("Cập nhật thông tin cá nhân thành công", updatedProfile);
    }

    private String resolveCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(r -> r.startsWith("ROLE_"))
                    .map(r -> r.substring(5))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    @PreAuthorize("hasAnyRole('ADMIN','TENANT_OWNER')")
    @PostMapping
    public ApiResponse<UserProfileResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        Long callerId = resolveCurrentUserId();
        String callerRole = resolveCurrentUserRole();
        UserProfileResponse response = userService.createUser(callerId, callerRole, request);
        return ApiResponse.success("Tạo tài khoản thành công", response);
    }

    @PreAuthorize("hasAnyRole('ADMIN','TENANT_OWNER')")
    @GetMapping
    public ApiResponse<PageResponse<UserProfileResponse>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        Long callerId = resolveCurrentUserId();
        String callerRole = resolveCurrentUserRole();
        PageResponse<UserProfileResponse> pageResponse = userService.listUsers(callerId, callerRole, keyword, status, role, page, size, sortBy, sortDir);
        return ApiResponse.success("Lấy danh sách người dùng thành công", pageResponse);
    }

    @PreAuthorize("hasAnyRole('ADMIN','TENANT_OWNER')")
    @PutMapping("/{id}")
    public ApiResponse<UserProfileResponse> adminUpdateUser(@PathVariable("id") Long id,
                                                             @Valid @RequestBody AdminUpdateUserRequest request) {
        Long callerId = resolveCurrentUserId();
        String callerRole = resolveCurrentUserRole();
        UserProfileResponse response = userService.adminUpdateUser(callerId, callerRole, id, request);
        return ApiResponse.success("Cập nhật người dùng thành công", response);
    }

    @PreAuthorize("hasAnyRole('ADMIN','TENANT_OWNER')")
    @PatchMapping("/{id}/status")
    public ApiResponse<UserProfileResponse> updateUserStatus(@PathVariable("id") Long id,
                                                               @Valid @RequestBody UpdateUserStatusRequest request) {
        Long callerId = resolveCurrentUserId();
        String callerRole = resolveCurrentUserRole();
        UserProfileResponse response = userService.updateUserStatus(callerId, callerRole, id, request);
        return ApiResponse.success("Thay đổi trạng thái người dùng thành công", response);
    }

    @PreAuthorize("hasAnyRole('ADMIN','TENANT_OWNER')")
    @PutMapping("/{id}/roles")
    public ApiResponse<UserProfileResponse> assignRoles(@PathVariable("id") Long id,
                                                          @Valid @RequestBody AssignRolesRequest request) {
        Long callerId = resolveCurrentUserId();
        String callerRole = resolveCurrentUserRole();
        UserProfileResponse response = userService.assignRoles(callerId, callerRole, id, request);
        return ApiResponse.success("Gán role thành công", response);
    }



    private Long resolveCurrentUserId() {
        Long userId = TenantContext.getCurrentUserId();
        if (userId != null) {
            return userId;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            Object principal = auth.getPrincipal();
            if (principal instanceof Long id) {
                return id;
            } else if (principal instanceof Number num) {
                return num.longValue();
            } else if (principal instanceof String str && !"anonymousUser".equalsIgnoreCase(str)) {
                try {
                    return Long.parseLong(str);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        throw new BusinessException("Chưa đăng nhập hoặc phiên làm việc đã hết hạn", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }
}

