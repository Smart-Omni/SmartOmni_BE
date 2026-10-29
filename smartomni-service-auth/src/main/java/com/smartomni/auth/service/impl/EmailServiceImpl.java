package com.smartomni.auth.service.impl;

import com.smartomni.auth.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${smartomni.mail.from:noreply@smartomni.vn}")
    private String mailFrom;

    @Value("${smartomni.auth.reset-password-url:https://smartomni.vn/reset-password?token=}")
    private String resetPasswordBaseUrl;

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        String resetLink = resetPasswordBaseUrl + resetToken;
        String subject = "[SmartOmni] Yêu cầu đặt lại mật khẩu";
        String content = String.format(
                "Xin chào,\n\n" +
                "Bạn đã yêu cầu đặt lại mật khẩu cho tài khoản tại SmartOmni.\n\n" +
                "Mã Token đặt lại mật khẩu của bạn là: %s\n\n" +
                "Hoặc bạn có thể nhấp vào liên kết sau để đặt lại mật khẩu:\n%s\n\n" +
                "Liên kết và mã token này có hiệu lực trong vòng 30 phút.\n" +
                "Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này.\n\n" +
                "Trân trọng,\nĐội ngũ hỗ trợ SmartOmni",
                resetToken,
                resetLink
        );

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(mailFrom);
                message.setTo(toEmail);
                message.setSubject(subject);
                message.setText(content);
                mailSender.send(message);
                log.info("Sent password reset email successfully to: {}", toEmail);
            } catch (Exception e) {
                log.error("Failed to send password reset email to {}: {}", toEmail, e.getMessage(), e);
            }
        } else {
            log.info("JavaMailSender bean is not configured. Simulated sending password reset email to {}: token={}, link={}",
                    toEmail, resetToken, resetLink);
        }
    }
}

