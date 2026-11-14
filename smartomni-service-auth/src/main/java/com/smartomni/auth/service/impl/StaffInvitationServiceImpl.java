package com.smartomni.auth.service.impl;

import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.CreateStaffInvitationRequest;
import com.smartomni.auth.dto.StaffInvitationResponse;
import com.smartomni.auth.entity.StaffInvitation;
import com.smartomni.auth.entity.StaffInvitation.InvitationStatus;
import com.smartomni.auth.entity.User;
import com.smartomni.auth.repository.StaffInvitationRepository;
import com.smartomni.auth.repository.TenantLookupRepository;
import com.smartomni.auth.repository.TenantLookupRepository.TenantLookup;
import com.smartomni.auth.repository.UserRepository;
import com.smartomni.auth.service.StaffInvitationService;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.InvalidTokenException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.persistence.RlsSession;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.security.UserAccessState;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class StaffInvitationServiceImpl implements StaffInvitationService {

    private static final String REDIS_PREFIX_REFRESH = "auth:refresh:";
    private static final String REDIS_PREFIX_USER_REFRESH = "auth:user_refresh:";
    private static final long INVITATION_TTL_DAYS = 7;

    private final StaffInvitationRepository staffInvitationRepository;
    private final UserRepository userRepository;
    private final TenantLookupRepository tenantLookupRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final EntityManager entityManager;

    @Value("${smartomni.jwt.expiration-ms:86400000}")
    private long expirationMs = 86400000L;

    @Value("${smartomni.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs = 604800000L;

    @Override
    @Transactional
    public StaffInvitationResponse createInvitation(Long inviterUserId, Long tenantId, CreateStaffInvitationRequest request) {
        if (tenantId == null) {
            throw new BusinessException("Không xác định được cửa hàng của quản trị viên", HttpStatus.FORBIDDEN, "TENANT_REQUIRED");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String targetRole = resolveInvitationRole(request.getRole());

        setRlsContext("app.tenant_id", tenantId.toString());
        setRlsContext("app.auth_email", normalizedEmail);

        User targetUser = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(
                        "Không tìm thấy tài khoản với email: " + normalizedEmail,
                        HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND"));

        if (targetUser.getTenantId() != null) {
            throw new BusinessException(
                    "Người dùng đã thuộc một cửa hàng khác",
                    HttpStatus.BAD_REQUEST,
                    "USER_ALREADY_IN_TENANT");
        }

        if (targetUser.getRole() != User.UserRole.STAFF) {
            throw new BusinessException(
                    "Tài khoản được mời phải là tài khoản nhân viên (STAFF)",
                    HttpStatus.BAD_REQUEST,
                    "USER_NOT_STAFF");
        }

        if (staffInvitationRepository.existsByTenantIdAndInvitedEmailIgnoreCaseAndStatus(
                tenantId, normalizedEmail, InvitationStatus.PENDING)) {
            throw new BusinessException(
                    "Đã tồn tại lời mời đang chờ xử lý cho email này",
                    HttpStatus.CONFLICT,
                    "INVITATION_ALREADY_PENDING");
        }

        setRlsContext("app.user_role", "super_admin");
        TenantLookup tenant = tenantLookupRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cửa hàng với id: " + tenantId));

        if (tenant.isLocked()) {
            throw new BusinessException("Cửa hàng hiện đang bị khóa", HttpStatus.FORBIDDEN, "TENANT_LOCKED");
        }

        StaffInvitation invitation = StaffInvitation.builder()
                .invitedEmail(normalizedEmail)
                .invitedUserId(targetUser.getId())
                .role(targetRole)
                .status(InvitationStatus.PENDING)
                .invitedBy(inviterUserId)
                .expiresAt(Instant.now().plus(INVITATION_TTL_DAYS, ChronoUnit.DAYS))
                .build();
        invitation.setTenantId(tenantId);

        StaffInvitation saved = staffInvitationRepository.save(invitation);
        log.info("Created staff invitation {} for email {} in tenant {}", saved.getId(), normalizedEmail, tenantId);
        return toResponse(saved, tenant);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffInvitationResponse> listTenantInvitations(Long tenantId) {
        if (tenantId == null) {
            throw new BusinessException("Không xác định được cửa hàng của quản trị viên", HttpStatus.FORBIDDEN, "TENANT_REQUIRED");
        }
        setRlsContext("app.tenant_id", tenantId.toString());
        setRlsContext("app.user_role", "super_admin");
        TenantLookup tenant = tenantLookupRepository.findById(tenantId).orElse(null);

        return staffInvitationRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(inv -> toResponse(inv, tenant))
                .toList();
    }

    @Override
    @Transactional
    public void cancelInvitation(Long tenantId, Long invitationId) {
        if (tenantId == null) {
            throw new BusinessException("Không xác định được cửa hàng của quản trị viên", HttpStatus.FORBIDDEN, "TENANT_REQUIRED");
        }
        setRlsContext("app.tenant_id", tenantId.toString());

        StaffInvitation invitation = staffInvitationRepository.findByIdAndTenantId(invitationId, tenantId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy lời mời", HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException("Chỉ có thể hủy lời mời đang ở trạng thái PENDING", HttpStatus.BAD_REQUEST, "INVITATION_NOT_PENDING");
        }

        invitation.setStatus(InvitationStatus.CANCELLED);
        invitation.setRespondedAt(Instant.now());
        staffInvitationRepository.save(invitation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffInvitationResponse> listMyInvitations(Long userId) {
        setRlsContext("app.auth_user_id", userId.toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        setRlsContext("app.auth_email", user.getEmail());
        setRlsContext("app.user_role", "super_admin");

        List<StaffInvitation> invitations = staffInvitationRepository.findForInvitee(userId, user.getEmail());
        Map<Long, TenantLookup> tenantCache = new HashMap<>();
        return invitations.stream()
                .map(inv -> {
                    TenantLookup tenant = tenantCache.computeIfAbsent(
                            inv.getTenantId(),
                            id -> tenantLookupRepository.findById(id).orElse(null));
                    return toResponse(inv, tenant);
                })
                .toList();
    }

    @Override
    @Transactional
    public AuthResponse acceptInvitation(Long userId, Long invitationId) {
        setRlsContext("app.auth_user_id", userId.toString());
        setRlsContext("app.user_role", "super_admin");

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        if (user.getTenantId() != null) {
            throw new BusinessException("Tài khoản đã thuộc một cửa hàng khác", HttpStatus.BAD_REQUEST, "USER_ALREADY_IN_TENANT");
        }

        setRlsContext("app.auth_email", user.getEmail());

        StaffInvitation invitation = staffInvitationRepository.findByIdForInvitee(invitationId, userId, user.getEmail())
                .orElseThrow(() -> new BusinessException("Không tìm thấy lời mời", HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException("Lời mời không còn ở trạng thái chờ xử lý", HttpStatus.BAD_REQUEST, "INVITATION_NOT_PENDING");
        }

        Instant now = Instant.now();
        if (invitation.getExpiresAt().isBefore(now)) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            staffInvitationRepository.save(invitation);
            throw new BusinessException("Lời mời đã hết hạn", HttpStatus.BAD_REQUEST, "INVITATION_EXPIRED");
        }

        TenantLookup tenant = tenantLookupRepository.findById(invitation.getTenantId())
                .orElseThrow(() -> new BusinessException("Không tìm thấy cửa hàng của lời mời", HttpStatus.NOT_FOUND, "STORE_NOT_FOUND"));

        if (tenant.isLocked()) {
            throw new BusinessException("Cửa hàng hiện đang bị khóa", HttpStatus.FORBIDDEN, "TENANT_LOCKED");
        }

        User.UserRole assignedRole = User.UserRole.valueOf(invitation.getRole().toUpperCase(Locale.ROOT));
        user.setTenantId(invitation.getTenantId());
        user.setRole(assignedRole);
        User savedUser = userRepository.save(user);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setInvitedUserId(savedUser.getId());
        invitation.setRespondedAt(now);
        staffInvitationRepository.save(invitation);

        List<StaffInvitation> otherPending = staffInvitationRepository.findOtherPendingForInvitee(
                invitationId, savedUser.getId(), savedUser.getEmail(), InvitationStatus.PENDING);
        if (!otherPending.isEmpty()) {
            for (StaffInvitation other : otherPending) {
                other.setStatus(InvitationStatus.CANCELLED);
                other.setRespondedAt(now);
            }
            staffInvitationRepository.saveAll(otherPending);
        }

        if (!UserAccessState.publishActive(stringRedisTemplate, savedUser.getId(), savedUser.getTokenVersion())) {
            throw new InvalidTokenException("Phiên đăng nhập đã bị thu hồi");
        }

        String accessToken = jwtTokenProvider.generateToken(
                savedUser.getId(),
                savedUser.getRole().name(),
                savedUser.getTenantId(),
                tenant.subdomain(),
                savedUser.getTokenVersion());
        String refreshToken = storeRefreshTokenInRedis(savedUser.getId());

        AuthResponse.UserInfo userInfo = AuthResponse.UserInfo.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .fullName(savedUser.getFullName())
                .phone(savedUser.getPhone())
                .avatar(savedUser.getAvatar())
                .role(savedUser.getRole().name())
                .tenantId(savedUser.getTenantId())
                .subdomain(tenant.subdomain())
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
    public StaffInvitationResponse declineInvitation(Long userId, Long invitationId) {
        setRlsContext("app.auth_user_id", userId.toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        setRlsContext("app.auth_email", user.getEmail());

        StaffInvitation invitation = staffInvitationRepository.findByIdForInvitee(invitationId, userId, user.getEmail())
                .orElseThrow(() -> new BusinessException("Không tìm thấy lời mời", HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException("Lời mời không còn ở trạng thái chờ xử lý", HttpStatus.BAD_REQUEST, "INVITATION_NOT_PENDING");
        }

        invitation.setStatus(InvitationStatus.DECLINED);
        invitation.setInvitedUserId(userId);
        invitation.setRespondedAt(Instant.now());
        StaffInvitation saved = staffInvitationRepository.save(invitation);

        setRlsContext("app.user_role", "super_admin");
        TenantLookup tenant = tenantLookupRepository.findById(saved.getTenantId()).orElse(null);
        return toResponse(saved, tenant);
    }

    private String resolveInvitationRole(String rawRole) {
        if (rawRole == null || rawRole.trim().isEmpty()) {
            return "STAFF";
        }
        String normalized = rawRole.trim().toUpperCase(Locale.ROOT);
        if ("STAFF".equals(normalized) || "MANAGER".equals(normalized)) {
            return normalized;
        }
        throw new BusinessException("Vai trò lời mời không hợp lệ. Chỉ cho phép STAFF hoặc MANAGER",
                HttpStatus.BAD_REQUEST, "INVALID_ROLE");
    }

    private String storeRefreshTokenInRedis(Long userId) {
        String oldRefreshToken = stringRedisTemplate.opsForValue().get(REDIS_PREFIX_USER_REFRESH + userId);
        if (oldRefreshToken != null) {
            stringRedisTemplate.delete(REDIS_PREFIX_REFRESH + oldRefreshToken);
        }

        String newRefreshToken = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(
                REDIS_PREFIX_REFRESH + newRefreshToken,
                userId.toString(),
                refreshExpirationMs,
                TimeUnit.MILLISECONDS);
        stringRedisTemplate.opsForValue().set(
                REDIS_PREFIX_USER_REFRESH + userId,
                newRefreshToken,
                refreshExpirationMs,
                TimeUnit.MILLISECONDS);
        return newRefreshToken;
    }

    private void setRlsContext(String name, String value) {
        if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
            RlsSession.set(entityManager, name, value);
        }
    }

    private StaffInvitationResponse toResponse(StaffInvitation inv, TenantLookup tenant) {
        return StaffInvitationResponse.builder()
                .id(inv.getId())
                .tenantId(inv.getTenantId())
                .companyName(tenant != null ? tenant.name() : null)
                .subdomain(tenant != null ? tenant.subdomain() : null)
                .invitedEmail(inv.getInvitedEmail())
                .invitedUserId(inv.getInvitedUserId())
                .role(inv.getRole())
                .status(inv.getStatus().name())
                .invitedBy(inv.getInvitedBy())
                .expiresAt(inv.getExpiresAt())
                .respondedAt(inv.getRespondedAt())
                .createdAt(inv.getCreatedAt())
                .build();
    }
}
