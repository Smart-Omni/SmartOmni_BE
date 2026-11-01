package com.smartomni.auth.service;

import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.LoginRequest;
import com.smartomni.auth.entity.User;
import com.smartomni.auth.repository.PasswordResetTokenRepository;
import com.smartomni.auth.repository.TenantLookupRepository;
import com.smartomni.auth.repository.TenantLookupRepository.TenantLookup;
import com.smartomni.auth.repository.UserRepository;
import com.smartomni.auth.service.impl.AuthServiceImpl;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantLookupRepository tenantLookupRepository;

    @Mock
    private PasswordResetTokenRepository resetTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private EntityManager entityManager;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserLoginAttemptService userLoginAttemptService;

    @Mock
    private EmailService emailService;

    private AuthServiceImpl authService;

    private User sampleUser;
    private TenantLookup sampleTenant;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        authService = new AuthServiceImpl(
                userRepository,
                tenantLookupRepository,
                resetTokenRepository,
                passwordEncoder,
                jwtTokenProvider,
                entityManager,
                stringRedisTemplate,
                userLoginAttemptService,
                emailService
        );
        ReflectionTestUtils.setField(authService, "expirationMs", 86400000L);
        ReflectionTestUtils.setField(authService, "refreshExpirationMs", 604800000L);

        sampleTenant = new TenantLookup(100L, "Smart Shop", "smartshop", "active", null);

        sampleUser = User.builder()
                .email("admin@smartshop.vn")
                .passwordHash("$2a$10$encryptedPassword")
                .fullName("Nguyen Van A")
                .role(User.UserRole.ADMIN)
                .status(User.UserStatus.ACTIVE)
                .failedLoginAttempts(0)
                .build();
        ReflectionTestUtils.setField(sampleUser, "id", 1L);
        ReflectionTestUtils.setField(sampleUser, "tenantId", 100L);
    }

    @Test
    @DisplayName("Đăng nhập thành công với đầy đủ thông tin subdomain hợp lệ")
    void testLogin_Success_WithSubdomain() {
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .subdomain("smartshop")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("Password123@", sampleUser.getPasswordHash())).thenReturn(true);
        when(tenantLookupRepository.findBySubdomain("smartshop")).thenReturn(Optional.of(sampleTenant));
        when(jwtTokenProvider.generateToken(1L, "ADMIN", 100L, "smartshop")).thenReturn("mocked.jwt.token");

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("mocked.jwt.token", response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(86400L, response.getExpiresIn());
        assertNotNull(response.getUserInfo());
        assertEquals(1L, response.getUserInfo().getId());
        assertEquals("admin@smartshop.vn", response.getUserInfo().getEmail());
        assertEquals("ADMIN", response.getUserInfo().getRole());
        assertEquals(100L, response.getUserInfo().getTenantId());
        assertEquals("smartshop", response.getUserInfo().getSubdomain());

        assertEquals(0, sampleUser.getFailedLoginAttempts());
        assertNull(sampleUser.getLockedUntil());
        verify(userRepository, times(1)).save(sampleUser);
        verify(userLoginAttemptService, times(1)).resetFailedAttempts(1L);
    }

    @Test
    @DisplayName("Đăng nhập thành công với tenant_id")
    void testLogin_Success_WithTenantId() {
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .tenantId(100L)
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("Password123@", sampleUser.getPasswordHash())).thenReturn(true);
        when(tenantLookupRepository.findById(100L)).thenReturn(Optional.of(sampleTenant));
        when(jwtTokenProvider.generateToken(1L, "ADMIN", 100L, "smartshop")).thenReturn("mocked.jwt.token");

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("mocked.jwt.token", response.getAccessToken());
        assertEquals(100L, response.getUserInfo().getTenantId());
        verify(userLoginAttemptService, times(1)).resetFailedAttempts(1L);
    }

    @Test
    @DisplayName("Đăng nhập thất bại do sai email")
    void testLogin_Failure_UserNotFound() {
        LoginRequest request = LoginRequest.builder()
                .email("notfound@smartshop.vn")
                .password("Password123@")
                .build();

        when(userRepository.findByEmail("notfound@smartshop.vn")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(request));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getHttpStatus());
        assertEquals("INVALID_CREDENTIALS", ex.getErrorCode());
        verify(userLoginAttemptService, never()).recordFailedAttempt(any());
    }

    @Test
    @DisplayName("Đăng nhập thất bại do sai mật khẩu và gọi recordFailedAttempt")
    void testLogin_Failure_WrongPassword() {
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("WrongPassword")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("WrongPassword", sampleUser.getPasswordHash())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(request));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getHttpStatus());
        assertEquals("INVALID_CREDENTIALS", ex.getErrorCode());

        verify(userLoginAttemptService, times(1)).recordFailedAttempt(1L);
    }

    @Test
    @DisplayName("Đăng nhập thất bại do sai mật khẩu kích hoạt recordFailedAttempt")
    void testLogin_Failure_AccountLocked_After5Attempts() {
        sampleUser.setFailedLoginAttempts(4);
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("WrongPassword")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("WrongPassword", sampleUser.getPasswordHash())).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(request));

        verify(userLoginAttemptService, times(1)).recordFailedAttempt(1L);
    }

    @Test
    @DisplayName("Đăng nhập thất bại khi tài khoản đang trong thời gian bị khóa")
    void testLogin_Failure_AccountCurrentlyLocked() {
        sampleUser.setLockedUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus());
        assertEquals("ACCOUNT_TEMP_LOCKED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Đăng nhập thất bại khi tài khoản bị vô hiệu hóa (DISABLED)")
    void testLogin_Failure_AccountDisabled() {
        sampleUser.setStatus(User.UserStatus.DISABLED);
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus());
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Đăng nhập thất bại khi Subdomain không tồn tại")
    void testLogin_Failure_SubdomainNotFound() {
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .subdomain("nonexistent")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("Password123@", sampleUser.getPasswordHash())).thenReturn(true);
        when(tenantLookupRepository.findBySubdomain("nonexistent")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> authService.login(request));
    }

    @Test
    @DisplayName("Đăng nhập thất bại khi Tenant đang ở trạng thái LOCKED")
    void testLogin_Failure_TenantLocked() {
        TenantLookup lockedTenant = new TenantLookup(100L, "Smart Shop", "smartshop", "locked", "Quá hạn thanh toán gói dịch vụ");

        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .subdomain("smartshop")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("Password123@", sampleUser.getPasswordHash())).thenReturn(true);
        when(tenantLookupRepository.findBySubdomain("smartshop")).thenReturn(Optional.of(lockedTenant));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus());
        assertEquals("TENANT_LOCKED", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Quá hạn thanh toán"));
    }

    @Test
    @DisplayName("Đăng nhập thất bại khi người dùng đăng nhập vào Tenant khác với Tenant được phân quyền")
    void testLogin_Failure_UserBelongsToDifferentTenant() {
        TenantLookup otherTenant = new TenantLookup(999L, "Other Shop", "othershop", "active", null);

        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .subdomain("othershop")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("Password123@", sampleUser.getPasswordHash())).thenReturn(true);
        when(tenantLookupRepository.findBySubdomain("othershop")).thenReturn(Optional.of(otherTenant));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(request));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getHttpStatus());
        assertEquals("INVALID_TENANT", ex.getErrorCode());
    }

    @Test
    @DisplayName("Làm mới token thành công khi Refresh Token hợp lệ trong Redis")
    void testRefreshToken_Success() {
        com.smartomni.auth.dto.RefreshTokenRequest request = new com.smartomni.auth.dto.RefreshTokenRequest("valid-refresh-token");

        when(valueOperations.get(AuthServiceImpl.REDIS_PREFIX_REFRESH + "valid-refresh-token")).thenReturn("1");
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(tenantLookupRepository.findById(100L)).thenReturn(Optional.of(sampleTenant));
        when(jwtTokenProvider.generateToken(1L, "ADMIN", 100L, "smartshop")).thenReturn("new.jwt.access.token");

        AuthResponse response = authService.refreshToken(request);

        assertNotNull(response);
        assertEquals("new.jwt.access.token", response.getAccessToken());
        assertNotNull(response.getRefreshToken());
        assertEquals(86400L, response.getExpiresIn());
        assertEquals("ADMIN", response.getUserInfo().getRole());
        verify(stringRedisTemplate, times(1)).delete(AuthServiceImpl.REDIS_PREFIX_REFRESH + "valid-refresh-token");
    }

    @Test
    @DisplayName("Làm mới token thất bại khi Refresh Token không tồn tại trong Redis")
    void testRefreshToken_Failure_NotFoundInRedis() {
        com.smartomni.auth.dto.RefreshTokenRequest request = new com.smartomni.auth.dto.RefreshTokenRequest("invalid-or-expired-token");

        when(valueOperations.get(AuthServiceImpl.REDIS_PREFIX_REFRESH + "invalid-or-expired-token")).thenReturn(null);

        assertThrows(com.smartomni.common.exception.InvalidTokenException.class,
                () -> authService.refreshToken(request));
    }

    @Test
    @DisplayName("Đăng xuất thành công: đưa access token vào blacklist Redis và xóa refresh token")
    void testLogout_Success() {
        String authHeader = "Bearer valid.access.token";

        when(jwtTokenProvider.validateToken("valid.access.token")).thenReturn(true);
        when(jwtTokenProvider.getRemainingExpirationMs("valid.access.token")).thenReturn(3600000L);
        when(jwtTokenProvider.getUserId("valid.access.token")).thenReturn(1L);
        when(valueOperations.get(AuthServiceImpl.REDIS_PREFIX_USER_REFRESH + 1L)).thenReturn("user-refresh-token");

        authService.logout(authHeader);

        verify(valueOperations, times(1)).set(
                eq(AuthServiceImpl.REDIS_PREFIX_BLACKLIST + "valid.access.token"),
                eq("revoked"),
                eq(3600000L),
                eq(java.util.concurrent.TimeUnit.MILLISECONDS)
        );
        verify(stringRedisTemplate, times(1)).delete(AuthServiceImpl.REDIS_PREFIX_REFRESH + "user-refresh-token");
        verify(stringRedisTemplate, times(1)).delete(AuthServiceImpl.REDIS_PREFIX_USER_REFRESH + 1L);
    }

    @Test
    @DisplayName("evictTenantSessions: Đặt cờ Redis auth:tenant_locked và thu hồi refresh tokens của mọi user thuộc tenant")
    void testEvictTenantSessions_Success() {
        when(userRepository.findByTenantId(100L)).thenReturn(java.util.List.of(sampleUser));
        when(valueOperations.get(AuthServiceImpl.REDIS_PREFIX_USER_REFRESH + 1L)).thenReturn("refresh-token-1");

        authService.evictTenantSessions(100L);

        verify(valueOperations).set("auth:tenant_locked:100", "LOCKED", 24L, java.util.concurrent.TimeUnit.HOURS);
        verify(stringRedisTemplate).delete(AuthServiceImpl.REDIS_PREFIX_REFRESH + "refresh-token-1");
        verify(stringRedisTemplate).delete(AuthServiceImpl.REDIS_PREFIX_USER_REFRESH + 1L);
    }

    @Test
    @DisplayName("Đăng ký tài khoản mới thành công")
    void testRegister_Success() {
        com.smartomni.auth.dto.RegisterRequest request = com.smartomni.auth.dto.RegisterRequest.builder()
                .email("newuser@example.com")
                .password("Password123@")
                .fullName("Nguyen Van A")
                .phone("0912345678")
                .avatar("https://example.com/avatar.png")
                .build();

        when(userRepository.findByEmail("newuser@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Password123@")).thenReturn("$2a$10$encodedPassword");

        User savedUser = User.builder()
                .email("newuser@example.com")
                .passwordHash("$2a$10$encodedPassword")
                .fullName("Nguyen Van A")
                .phone("0912345678")
                .avatar("https://example.com/avatar.png")
                .role(User.UserRole.CUSTOMER)
                .status(User.UserStatus.ACTIVE)
                .build();
        savedUser.setId(2L);

        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtTokenProvider.generateToken(2L, "CUSTOMER", null, null)).thenReturn("jwt.token.newuser");

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("jwt.token.newuser", response.getAccessToken());
        assertEquals("CUSTOMER", response.getUserInfo().getRole());
        assertEquals("Nguyen Van A", response.getUserInfo().getFullName());
        assertEquals("0912345678", response.getUserInfo().getPhone());
        assertNull(response.getUserInfo().getTenantId());
        assertNull(response.getUserInfo().getSubdomain());
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Đăng ký thất bại khi email đã tồn tại")
    void testRegister_DuplicateEmail() {
        com.smartomni.auth.dto.RegisterRequest request = com.smartomni.auth.dto.RegisterRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .fullName("Admin Duplicate")
                .build();

        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(request));
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        assertEquals("EMAIL_ALREADY_EXISTS", ex.getErrorCode());
    }

    @Test
    @DisplayName("Đổi mật khẩu thành công")
    void testChangePassword_Success() {
        com.smartomni.auth.dto.ChangePasswordRequest request = com.smartomni.auth.dto.ChangePasswordRequest.builder()
                .currentPassword("OldPassword123")
                .newPassword("NewPassword456")
                .confirmPassword("NewPassword456")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("OldPassword123", sampleUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("NewPassword456")).thenReturn("$2a$10$newEncodedPassword");
        when(valueOperations.get(AuthServiceImpl.REDIS_PREFIX_USER_REFRESH + 1L)).thenReturn("old-refresh-token");

        authService.changePassword(1L, request);

        assertEquals("$2a$10$newEncodedPassword", sampleUser.getPasswordHash());
        verify(userRepository).save(sampleUser);
        verify(stringRedisTemplate).delete(AuthServiceImpl.REDIS_PREFIX_REFRESH + "old-refresh-token");
        verify(stringRedisTemplate).delete(AuthServiceImpl.REDIS_PREFIX_USER_REFRESH + 1L);
    }

    @Test
    @DisplayName("Đổi mật khẩu thất bại khi mật khẩu xác nhận không khớp")
    void testChangePassword_MismatchConfirmPassword() {
        com.smartomni.auth.dto.ChangePasswordRequest request = com.smartomni.auth.dto.ChangePasswordRequest.builder()
                .currentPassword("OldPassword123")
                .newPassword("NewPassword456")
                .confirmPassword("DifferentPassword")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.changePassword(1L, request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("PASSWORD_MISMATCH", ex.getErrorCode());
    }

    @Test
    @DisplayName("Đổi mật khẩu thất bại khi mật khẩu hiện tại không chính xác")
    void testChangePassword_InvalidCurrentPassword() {
        com.smartomni.auth.dto.ChangePasswordRequest request = com.smartomni.auth.dto.ChangePasswordRequest.builder()
                .currentPassword("WrongPassword")
                .newPassword("NewPassword456")
                .confirmPassword("NewPassword456")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("WrongPassword", sampleUser.getPasswordHash())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.changePassword(1L, request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("INVALID_CURRENT_PASSWORD", ex.getErrorCode());
    }

    @Test
    @DisplayName("forgotPassword: tạo token và gửi email khi email tồn tại")
    void testForgotPassword_Success() {
        when(userRepository.findByEmail("admin@smartshop.vn")).thenReturn(Optional.of(sampleUser));

        authService.forgotPassword("admin@smartshop.vn");

        verify(resetTokenRepository).save(any(com.smartomni.auth.entity.PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(eq("admin@smartshop.vn"), anyString());
    }

    @Test
    @DisplayName("resetPassword: cập nhật mật khẩu mới khi token hợp lệ và còn hạn")
    void testResetPassword_Success() {
        com.smartomni.auth.entity.PasswordResetToken resetToken = new com.smartomni.auth.entity.PasswordResetToken();
        resetToken.setId(10L);
        resetToken.setUserId(1L);
        resetToken.setToken("valid-reset-token");
        resetToken.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        resetToken.setUsed(false);

        when(resetTokenRepository.findByTokenAndUsedFalse("valid-reset-token")).thenReturn(Optional.of(resetToken));
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.encode("brandNewPassword123")).thenReturn("$2a$10$brandNewHashed");

        authService.resetPassword("valid-reset-token", "brandNewPassword123");

        assertEquals("$2a$10$brandNewHashed", sampleUser.getPasswordHash());
        assertTrue(resetToken.isUsed());
        verify(userRepository).save(sampleUser);
        verify(resetTokenRepository).save(resetToken);
    }
}
