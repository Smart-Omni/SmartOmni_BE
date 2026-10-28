package com.smartomni.auth.service;

/**
 * Service gửi email thông báo hệ thống và mã/link khôi phục mật khẩu.
 */
public interface EmailService {

    /**
     * Gửi email hướng dẫn đặt lại mật khẩu kèm mã token/OTP hoặc link khôi phục.
     *
     * @param toEmail địa chỉ email người nhận
     * @param resetToken token khôi phục mật khẩu
     */
    void sendPasswordResetEmail(String toEmail, String resetToken);
}

