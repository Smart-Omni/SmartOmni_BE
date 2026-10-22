package com.smartomni.auth.controller;

import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.ForgotPasswordRequest;
import com.smartomni.auth.dto.LoginRequest;
import com.smartomni.auth.dto.RefreshTokenRequest;
import com.smartomni.auth.dto.ResetPasswordRequest;
import com.smartomni.auth.service.AuthService;
import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller cho các API Xác thực, cấp phát/làm mới Token và Đăng xuất (UC-01, UC-02, UC-03).
 */
@RestController
@RequestMapping({"/api/v1/auth", "/api/auth"})
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ApiResponse.success("Đặt lại mật khẩu thành công", null);
    }

    /**
     * POST /api/v1/auth/internal/tenants/{tenantId}/evict
     * Thu hồi toàn bộ phiên đăng nhập của Tenant bị khóa (kích hoạt Tenant Eviction).
     */
    @PostMapping("/internal/tenants/{tenantId}/evict")
    public ApiResponse<Void> evictTenantSessions(@org.springframework.web.bind.annotation.PathVariable Long tenantId) {
        authService.evictTenantSessions(tenantId);
        return ApiResponse.success("Đã thu hồi toàn bộ phiên của Tenant " + tenantId, null);
    }
}
