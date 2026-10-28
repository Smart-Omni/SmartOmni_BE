package com.smartomni.auth.controller;

import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.ChangePasswordRequest;
import com.smartomni.auth.dto.ForgotPasswordRequest;
import com.smartomni.auth.dto.LoginRequest;
import com.smartomni.auth.dto.RefreshTokenRequest;
import com.smartomni.auth.dto.RegisterRequest;
import com.smartomni.auth.dto.ResetPasswordRequest;
import com.smartomni.auth.service.AuthService;
import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.tenant.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller cho các API Xác thực, cấp phát/làm mới Token, Đăng xuất, Đăng ký và Quản lý Mật khẩu.
 */
@RestController
@RequestMapping({"/api/v1/auth", "/api/auth"})
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/v1/auth/register hoặc /api/auth/register
     * Đăng ký tài khoản người dùng mới (kiểm tra email trùng, hash password, gán role mặc định CUSTOMER).
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ApiResponse.success("Đăng ký tài khoản thành công", response);
    }

    /**
     * POST /api/v1/auth/login hoặc /api/auth/login
     * Đăng nhập hệ thống, kiểm tra xác thực, multi-tenancy, lưu Refresh Token vào Redis và cấp phát JWT.
     */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ApiResponse.success("Đăng nhập thành công", response);
    }

    /**
     * POST /api/v1/auth/refresh hoặc /api/auth/refresh
     * Cấp lại Access Token mới dựa trên Refresh Token lưu trong Redis.
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ApiResponse.success("Làm mới token thành công", response);
    }

    /**
     * POST /api/v1/auth/logout hoặc /api/auth/logout
     * Đăng xuất hệ thống: đưa Access Token vào Redis Blacklist và hủy Refresh Token.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = AppConstants.HEADER_AUTHORIZATION, required = false) String authHeader) {
        authService.logout(authHeader);
        return ApiResponse.success("Đăng xuất thành công", null);
    }

    /**
     * POST /api/v1/auth/change-password hoặc /api/auth/change-password
     * Đổi mật khẩu cho người dùng hiện tại (nhận currentPassword, newPassword, confirmPassword và kiểm tra mật khẩu cũ).
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long userId = resolveCurrentUserId();
        authService.changePassword(userId, request);
        return ApiResponse.success("Đổi mật khẩu thành công", null);
    }

    /**
     * POST /api/v1/auth/forgot-password hoặc /api/auth/forgot-password
     * Yêu cầu gửi mã / đường dẫn đặt lại mật khẩu qua email.
     */
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.getEmail());
        return ApiResponse.success("Nếu email tồn tại trong hệ thống, hướng dẫn đặt lại mật khẩu đã được gửi", null);
    }

    /**
     * POST /api/v1/auth/reset-password hoặc /api/auth/reset-password
     * Đặt lại mật khẩu mới thông qua reset token.
     */
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        if (request.getConfirmPassword() != null && !request.getConfirmPassword().trim().isEmpty()
                && !request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("Mật khẩu xác nhận không khớp với mật khẩu mới", HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH");
        }
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ApiResponse.success("Đặt lại mật khẩu thành công", null);
    }

    /**
     * POST /api/v1/auth/internal/tenants/{tenantId}/evict
     * Thu hồi toàn bộ phiên đăng nhập của Tenant bị khóa (kích hoạt Tenant Eviction).
     */
    @PostMapping("/internal/tenants/{tenantId}/evict")
    public ApiResponse<Void> evictTenantSessions(@PathVariable Long tenantId) {
        authService.evictTenantSessions(tenantId);
        return ApiResponse.success("Đã thu hồi toàn bộ phiên của Tenant " + tenantId, null);
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
