package com.smartomni.auth.service;

import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.ChangePasswordRequest;
import com.smartomni.auth.dto.LoginRequest;
import com.smartomni.auth.dto.RefreshTokenRequest;
import com.smartomni.auth.dto.RegisterRequest;

/**
 * Service interface cho nghiệp vụ xác thực người dùng, Refresh Token và Logout sử dụng Redis (UC-01, UC-02, UC-03).
 */
public interface AuthService {

    /**
     * Đăng ký tài khoản người dùng mới (kiểm tra email trùng, hash password, gán role mặc định).
     *
     * @param request thông tin đăng ký
     * @return AuthResponse chứa accessToken, refreshToken và thông tin người dùng
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Xác thực người dùng, kiểm tra multi-tenancy, lưu Refresh Token vào Redis và cấp phát JWT Access Token.
     *
     * @param request thông tin đăng nhập gồm email, password, và subdomain/tenantId tùy chọn
     * @return AuthResponse chứa accessToken, refreshToken, tokenType, expiresIn, và userInfo
     */
    AuthResponse login(LoginRequest request);

    /**
     * Cấp lại Access Token mới dựa trên Refresh Token hợp lệ lưu trong Redis.
     *
     * @param request chứa refresh_token
     * @return AuthResponse mới chứa accessToken mới, refreshToken (được rotate), expiresIn, và userInfo
     */
    AuthResponse refreshToken(RefreshTokenRequest request);

    /**
     * Đăng xuất người dùng: đưa Access Token hiện tại vào Redis Blacklist và xóa Refresh Token của user.
     *
     * @param authHeader giá trị header Authorization (dạng "Bearer <token>")
     */
    void logout(String authHeader);

    /**
     * Đổi mật khẩu cho người dùng hiện tại (kiểm tra mật khẩu cũ, mật khẩu mới và xác nhận mật khẩu).
     *
     * @param userId ID người dùng
     * @param request thông tin mật khẩu cũ và mới
     */
    void changePassword(Long userId, ChangePasswordRequest request);

    /**
     * Yêu cầu đặt lại mật khẩu qua email (UC-03 / FR-005).
     * Sinh token/OTP khôi phục có thời hạn và gửi qua email service.
     *
     * @param email địa chỉ email của người dùng
     */
    void forgotPassword(String email);

    /**
     * Đặt lại mật khẩu mới bằng token đã gửi qua email (UC-03 / FR-006).
     *
     * @param token reset token hợp lệ và còn hạn
     * @param newPassword mật khẩu mới chưa mã hóa
     */
    void resetPassword(String token, String newPassword);

    /**
     * Vô hiệu hóa toàn bộ phiên đăng nhập của Tenant:
     * - Đặt cờ auth:tenant_locked:<tenantId> trong Redis
     * - Thu hồi toàn bộ Refresh Tokens của mọi người dùng thuộc Tenant đó.
     *
     * @param tenantId ID của Tenant bị khóa
     */
    void evictTenantSessions(Long tenantId);
}
