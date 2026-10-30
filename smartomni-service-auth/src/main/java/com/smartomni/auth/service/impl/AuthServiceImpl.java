package com.smartomni.auth.service.impl;

import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.ChangePasswordRequest;
import com.smartomni.auth.dto.LoginRequest;
import com.smartomni.auth.dto.RefreshTokenRequest;
import com.smartomni.auth.dto.RegisterRequest;
import com.smartomni.auth.entity.PasswordResetToken;
import com.smartomni.auth.entity.User;
import com.smartomni.auth.repository.PasswordResetTokenRepository;
import com.smartomni.auth.repository.TenantLookupRepository;
import com.smartomni.auth.repository.TenantLookupRepository.TenantLookup;
import com.smartomni.auth.repository.UserRepository;
import com.smartomni.auth.service.AuthService;
import com.smartomni.auth.service.EmailService;
import com.smartomni.auth.service.UserLoginAttemptService;
import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.InvalidTokenException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.persistence.RlsSession;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MINUTES = 15;
    private static final long RESET_TOKEN_TTL_MINUTES = 30;

    public static final String REDIS_PREFIX_REFRESH = "auth:refresh_token:";
    public static final String REDIS_PREFIX_USER_REFRESH = "auth:user_refresh:";
    public static final String REDIS_PREFIX_BLACKLIST = AppConstants.REDIS_PREFIX_BLACKLIST;

    private final UserRepository userRepository;
    private final TenantLookupRepository tenantLookupRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final EntityManager entityManager;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserLoginAttemptService userLoginAttemptService;
    private final EmailService emailService;

    @Value("${smartomni.jwt.expiration-ms:86400000}")
    private long expirationMs;

    @Value("${smartomni.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        log.info("Processing standard register request for email: {}", request.getEmail());
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        setRlsContext("app.auth_email", email);
        setRlsContext("app.user_role", "super_admin");

        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException("Email đã được sử dụng trong hệ thống", HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
        }

        // Luồng 1: Đăng ký Chuẩn cho Customer/General User không gắn Tenant (tenant_id = null)
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName().trim())
                .phone(request.getPhone() != null && !request.getPhone().trim().isEmpty() ? request.getPhone().trim() : null)
                .avatar(request.getAvatar() != null && !request.getAvatar().trim().isEmpty() ? request.getAvatar().trim() : null)
                .role(User.UserRole.CUSTOMER)
                .status(User.UserStatus.ACTIVE)
                .failedLoginAttempts(0)
                .build();
        user.setTenantId(null);

        setRlsContext("app.auth_email", email);
        setRlsContext("app.user_role", "super_admin");
        User savedUser = userRepository.save(user);
        log.info("Successfully registered new standard user ID: {}, email: {}", savedUser.getId(), savedUser.getEmail());

        String accessToken = jwtTokenProvider.generateToken(savedUser.getId(), savedUser.getRole().name(), null, null);
        String refreshToken = storeRefreshTokenInRedis(savedUser.getId());

        AuthResponse.UserInfo userInfo = AuthResponse.UserInfo.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .fullName(savedUser.getFullName())
                .phone(savedUser.getPhone())
                .avatar(savedUser.getAvatar())
                .role(savedUser.getRole().name())
                .tenantId(null)
                .subdomain(null)
                .build();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expirationMs / 1000)
                .userInfo(userInfo)
                .build();
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.info("Processing login request for email: {}", request.getEmail());

        // Thiết lập context RLS cho truy vấn trước khi cấp JWT
        setRlsContext("app.auth_email", request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException("Email hoặc mật khẩu không chính xác", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"));

        // FR-007: Kiểm tra brute-force lock
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            log.warn("Account temporarily locked for user id: {}", user.getId());
            throw new BusinessException("Tài khoản đang bị tạm khóa do nhập sai mật khẩu quá 5 lần. Vui lòng thử lại sau.",
                    HttpStatus.FORBIDDEN, "ACCOUNT_TEMP_LOCKED");
        }

        if (user.getStatus() == User.UserStatus.DISABLED) {
            log.warn("Account is disabled for user id: {}", user.getId());
            throw new BusinessException("Tài khoản đã bị vô hiệu hóa", HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }

        // Xác thực mật khẩu với BCrypt
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Password mismatch for email: {}", request.getEmail());
            userLoginAttemptService.recordFailedAttempt(user.getId());
            throw new BusinessException("Email hoặc mật khẩu không chính xác", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        // FR-003: Cơ chế Multi-tenancy & kiểm tra tính hợp lệ của Tenant / Subdomain
        // Thiết lập context RLS phù hợp để bypass rào cản RLS của bảng tenants cho request chưa xác thực
        setRlsContext("app.user_role", "super_admin");

        String resolvedSubdomain = null;
        String requestedSubdomain = request.getSubdomain();
        if ((requestedSubdomain == null || requestedSubdomain.trim().isEmpty()) && TenantContext.getCurrentSubdomain() != null) {
            requestedSubdomain = TenantContext.getCurrentSubdomain();
        }

        if (requestedSubdomain != null && !requestedSubdomain.trim().isEmpty()) {
            String targetSubdomain = requestedSubdomain.trim();
            TenantLookup tenant = tenantLookupRepository.findBySubdomain(targetSubdomain)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tenant với subdomain: " + targetSubdomain));

            if (tenant.isLocked()) {
                String reason = tenant.lockedReason() != null ? tenant.lockedReason() : "Vui lòng liên hệ bộ phận hỗ trợ";
                throw new BusinessException("Tổ chức/Tenant '" + tenant.name() + "' đã bị khóa. Lý do: " + reason,
                        HttpStatus.FORBIDDEN, "TENANT_LOCKED");
            }

            if (user.getRole() != User.UserRole.SUPER_ADMIN && user.getTenantId() != null
                    && !user.getTenantId().equals(tenant.id())) {
                throw new BusinessException("Tài khoản không thuộc tenant '" + targetSubdomain + "'",
                        HttpStatus.UNAUTHORIZED, "INVALID_TENANT");
            }

            resolvedSubdomain = tenant.subdomain();
        } else if (request.getTenantId() != null) {
            TenantLookup tenant = tenantLookupRepository.findById(request.getTenantId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tenant với id: " + request.getTenantId()));

            if (tenant.isLocked()) {
                throw new BusinessException("Tổ chức/Tenant '" + tenant.name() + "' đã bị khóa",
                        HttpStatus.FORBIDDEN, "TENANT_LOCKED");
            }

            if (user.getRole() != User.UserRole.SUPER_ADMIN && user.getTenantId() != null
                    && !user.getTenantId().equals(request.getTenantId())) {
                throw new BusinessException("Tài khoản không thuộc tenant này",
                        HttpStatus.UNAUTHORIZED, "INVALID_TENANT");
            }

            resolvedSubdomain = tenant.subdomain();
        } else if (user.getTenantId() != null) {
            Optional<TenantLookup> userTenant = tenantLookupRepository.findById(user.getTenantId());
            if (userTenant.isPresent()) {
                TenantLookup tenant = userTenant.get();
                if (tenant.isLocked()) {
                    throw new BusinessException("Tổ chức/Tenant của bạn đã bị khóa. Vui lòng liên hệ quản trị viên.",
                            HttpStatus.FORBIDDEN, "TENANT_LOCKED");
                }
                resolvedSubdomain = tenant.subdomain();
            }
        }

        // Đăng nhập thành công -> Reset số lần đăng nhập sai
        userLoginAttemptService.resetFailedAttempts(user.getId());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // Sinh JWT Access Token chứa user_id, role, tenant_id, subdomain
        String accessToken = jwtTokenProvider.generateToken(user.getId(), user.getRole().name(), user.getTenantId(), resolvedSubdomain);

        // Sinh và lưu Refresh Token vào Redis
        String refreshToken = storeRefreshTokenInRedis(user.getId());

        AuthResponse.UserInfo userInfo = AuthResponse.UserInfo.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .role(user.getRole().name())
                .tenantId(user.getTenantId())
                .subdomain(resolvedSubdomain)
                .build();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expirationMs / 1000)
                .userInfo(userInfo)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        if (request == null || request.getRefreshToken() == null || request.getRefreshToken().trim().isEmpty()) {
            throw new InvalidTokenException("Refresh token không được để trống");
        }

        String refreshToken = request.getRefreshToken().trim();
        String userIdStr = stringRedisTemplate.opsForValue().get(REDIS_PREFIX_REFRESH + refreshToken);
        if (userIdStr == null) {
            log.warn("Refresh token not found or expired in Redis: {}", refreshToken);
            throw new InvalidTokenException("Refresh token không hợp lệ hoặc đã hết hạn");
        }

        Long userId = Long.valueOf(userIdStr);
        setRlsContext("app.auth_user_id", userId.toString());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Người dùng không tồn tại"));

        if (user.getStatus() == User.UserStatus.DISABLED) {
            throw new BusinessException("Tài khoản đã bị vô hiệu hóa", HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new BusinessException("Tài khoản đang bị tạm khóa", HttpStatus.FORBIDDEN, "ACCOUNT_TEMP_LOCKED");
        }

        String resolvedSubdomain = null;
        if (user.getTenantId() != null) {
            setRlsContext("app.user_role", "super_admin");
            Optional<TenantLookup> userTenant = tenantLookupRepository.findById(user.getTenantId());
            if (userTenant.isPresent()) {
                TenantLookup tenant = userTenant.get();
                if (tenant.isLocked()) {
                    throw new BusinessException("Tổ chức/Tenant đã bị khóa", HttpStatus.FORBIDDEN, "TENANT_LOCKED");
                }
                resolvedSubdomain = tenant.subdomain();
            }
        }

        // Cấp Access Token mới
        String newAccessToken = jwtTokenProvider.generateToken(user.getId(), user.getRole().name(), user.getTenantId(), resolvedSubdomain);

        // Rotate Refresh Token: Xóa token cũ và lưu token mới
        stringRedisTemplate.delete(REDIS_PREFIX_REFRESH + refreshToken);
        String newRefreshToken = storeRefreshTokenInRedis(user.getId());

        AuthResponse.UserInfo userInfo = AuthResponse.UserInfo.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .role(user.getRole().name())
                .tenantId(user.getTenantId())
                .subdomain(resolvedSubdomain)
                .build();

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(expirationMs / 1000)
                .userInfo(userInfo)
                .build();
    }

    @Override
    public void logout(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(AppConstants.BEARER_PREFIX)) {
            log.warn("Logout request without valid Bearer Authorization header");
            return;
        }

        String accessToken = authHeader.substring(AppConstants.BEARER_PREFIX.length()).trim();

        if (jwtTokenProvider.validateToken(accessToken)) {
            long remainingMs = jwtTokenProvider.getRemainingExpirationMs(accessToken);
            if (remainingMs > 0) {
                // Đưa accessToken vào Redis Blacklist với TTL bằng thời gian hết hạn còn lại
                stringRedisTemplate.opsForValue().set(
                        REDIS_PREFIX_BLACKLIST + accessToken,
                        "revoked",
                        remainingMs,
                        TimeUnit.MILLISECONDS
                );
                log.info("Blacklisted access token in Redis with TTL: {} ms", remainingMs);
            }

            // Xóa Refresh Token của user khỏi Redis
            Long userId = jwtTokenProvider.getUserId(accessToken);
            if (userId != null) {
                String existingRefreshToken = stringRedisTemplate.opsForValue().get(REDIS_PREFIX_USER_REFRESH + userId);
                if (existingRefreshToken != null) {
                    stringRedisTemplate.delete(REDIS_PREFIX_REFRESH + existingRefreshToken);
                }
                stringRedisTemplate.delete(REDIS_PREFIX_USER_REFRESH + userId);
                log.info("Deleted active refresh tokens for user id: {}", userId);
            }
        }

        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        if (userId == null) {
            throw new BusinessException("Chưa đăng nhập hoặc phiên làm việc đã hết hạn", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("Mật khẩu xác nhận không khớp với mật khẩu mới", HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH");
        }
        if (request.getNewPassword().equals(request.getCurrentPassword())) {
            throw new BusinessException("Mật khẩu mới không được trùng với mật khẩu hiện tại", HttpStatus.BAD_REQUEST, "PASSWORD_SAME_AS_CURRENT");
        }

        setRlsContext("app.auth_user_id", userId.toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Người dùng không tồn tại"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Mật khẩu hiện tại không chính xác", HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Successfully changed password for user ID: {}", userId);

        // Thu hồi refresh token cũ
        String existingRefreshToken = stringRedisTemplate.opsForValue().get(REDIS_PREFIX_USER_REFRESH + userId);
        if (existingRefreshToken != null) {
            stringRedisTemplate.delete(REDIS_PREFIX_REFRESH + existingRefreshToken);
        }
        stringRedisTemplate.delete(REDIS_PREFIX_USER_REFRESH + userId);
    }

    private String storeRefreshTokenInRedis(Long userId) {
        // Xóa refresh token cũ nếu có
        String oldRefreshToken = stringRedisTemplate.opsForValue().get(REDIS_PREFIX_USER_REFRESH + userId);
        if (oldRefreshToken != null) {
            stringRedisTemplate.delete(REDIS_PREFIX_REFRESH + oldRefreshToken);
        }

        String newRefreshToken = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(
                REDIS_PREFIX_REFRESH + newRefreshToken,
                userId.toString(),
                refreshExpirationMs,
                TimeUnit.MILLISECONDS
        );
        stringRedisTemplate.opsForValue().set(
                REDIS_PREFIX_USER_REFRESH + userId,
                newRefreshToken,
                refreshExpirationMs,
                TimeUnit.MILLISECONDS
        );
        return newRefreshToken;
    }

    @Override
    @Transactional
    public void forgotPassword(String email) {
        if (email == null || email.trim().isEmpty()) {
            return;
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        setRlsContext("app.auth_email", normalizedEmail);
        // FR-005: không tiết lộ email có tồn tại hay không vì lý do bảo mật
        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUserId(user.getId());
            resetToken.setToken(UUID.randomUUID().toString());
            resetToken.setExpiresAt(Instant.now().plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));
            resetTokenRepository.save(resetToken);

            log.info("Generated password reset token for user id: {}", user.getId());
            emailService.sendPasswordResetEmail(user.getEmail(), resetToken.getToken());
        });
    }

    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        setRlsContext("app.reset_token", token);
        PasswordResetToken resetToken = resetTokenRepository.findByTokenAndUsedFalse(token)
                .orElseThrow(() -> new BusinessException("Token không hợp lệ hoặc đã được sử dụng"));

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("Token đặt lại mật khẩu đã hết hạn");
        }

        setRlsContext("app.auth_user_id", resetToken.getUserId().toString());
        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BusinessException("Người dùng không tồn tại"));

        user.setPasswordHash(passwordEncoder.encode(newPassword)); // FR-006: hash mật khẩu trước khi lưu
        userRepository.save(user);

        resetToken.setUsed(true);
        resetTokenRepository.save(resetToken);
        log.info("Successfully reset password for user id: {}", user.getId());

        // Xóa refresh token cũ
        String existingRefreshToken = stringRedisTemplate.opsForValue().get(REDIS_PREFIX_USER_REFRESH + user.getId());
        if (existingRefreshToken != null) {
            stringRedisTemplate.delete(REDIS_PREFIX_REFRESH + existingRefreshToken);
        }
        stringRedisTemplate.delete(REDIS_PREFIX_USER_REFRESH + user.getId());
    }

    @Override
    @Transactional
    public void evictTenantSessions(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        log.info("Evicting all sessions and refresh tokens for tenant: {}", tenantId);

        // 1. Ghi cờ auth:tenant_locked:<tenantId> lên Redis
        stringRedisTemplate.opsForValue().set(
                AppConstants.REDIS_PREFIX_TENANT_LOCKED + tenantId,
                "LOCKED",
                24,
                TimeUnit.HOURS
        );

        // 2. Thu hồi toàn bộ Refresh Tokens của mọi người dùng thuộc Tenant đó
        setRlsContext("app.user_role", "super_admin");
        var users = userRepository.findByTenantId(tenantId);
        for (User user : users) {
            String existingRefreshToken = stringRedisTemplate.opsForValue().get(REDIS_PREFIX_USER_REFRESH + user.getId());
            if (existingRefreshToken != null) {
                stringRedisTemplate.delete(REDIS_PREFIX_REFRESH + existingRefreshToken);
            }
            stringRedisTemplate.delete(REDIS_PREFIX_USER_REFRESH + user.getId());
        }
        log.info("Successfully evicted sessions for {} users belonging to tenant: {}", users.size(), tenantId);
    }

    private void setRlsContext(String name, String value) {
        if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
            RlsSession.set(entityManager, name, value);
        }
    }
}
