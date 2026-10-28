package com.smartomni.auth.service.impl;

import com.smartomni.auth.dto.AdminUpdateUserRequest;
import com.smartomni.auth.dto.AssignRolesRequest;
import com.smartomni.auth.dto.CreateUserRequest;
import com.smartomni.auth.dto.PageResponse;
import com.smartomni.auth.dto.UpdateProfileRequest;
import com.smartomni.auth.dto.UpdateUserStatusRequest;
import com.smartomni.auth.dto.UserProfileResponse;
import com.smartomni.auth.entity.User;
import com.smartomni.auth.repository.TenantLookupRepository;
import com.smartomni.auth.repository.UserRepository;
import com.smartomni.auth.service.UserService;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import com.smartomni.common.persistence.RlsSession;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final TenantLookupRepository tenantLookupRepository;
    private final EntityManager entityManager;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile(Long userId) {
        if (userId == null) {
            throw new BusinessException("Chưa đăng nhập hoặc phiên đăng nhập không hợp lệ", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }

        setRlsContext("app.auth_user_id", userId.toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin người dùng với ID: " + userId));

        String subdomain = resolveSubdomain(user.getTenantId());
        return mapToProfileResponse(user, subdomain);
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentUserProfile(Long userId, UpdateProfileRequest request) {
        if (userId == null) {
            throw new BusinessException("Chưa đăng nhập hoặc phiên đăng nhập không hợp lệ", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }

        setRlsContext("app.auth_user_id", userId.toString());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin người dùng với ID: " + userId));

        if (request.getFullName() != null) {
            user.setFullName(request.getFullName().trim());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone().trim());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar().trim());
        }

        User updatedUser = userRepository.save(user);
        String subdomain = resolveSubdomain(updatedUser.getTenantId());
        return mapToProfileResponse(updatedUser, subdomain);
    }

    private String resolveSubdomain(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        setRlsContext("app.user_role", "super_admin");
        return tenantLookupRepository.findById(tenantId)
                .map(TenantLookupRepository.TenantLookup::subdomain)
                .orElse(null);
    }

    private UserProfileResponse mapToProfileResponse(User user, String subdomain) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .status(user.getStatus() != null ? user.getStatus().name() : null)
                .tenantId(user.getTenantId())
                .subdomain(subdomain)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    private void setRlsContext(String name, String value) {
        if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
            RlsSession.set(entityManager, name, value);
        }
    }

    // ======================== ADMIN APIs ========================

    @Override
    @Transactional
    public UserProfileResponse createUser(Long callerUserId, String callerRole, CreateUserRequest request) {
        // Resolve caller tenantId
        User caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Caller user not found"));
        Long tenantId = caller.getTenantId();

        // Validate role not SUPER_ADMIN
        User.UserRole newRole;
        try {
            newRole = User.UserRole.valueOf(request.getRole());
        } catch (Exception e) {
            throw new BusinessException("Role không hợp lệ", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }
        if (newRole == User.UserRole.SUPER_ADMIN) {
            throw new BusinessException("Không được tạo tài khoản SUPER_ADMIN", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        // Check duplicate email within tenant
        if (userRepository.findByEmailAndTenantId(request.getEmail(), tenantId).isPresent()) {
            throw new BusinessException("Email đã tồn tại trong tenant", HttpStatus.CONFLICT, "EMAIL_EXISTS");
        }

        // Generate password if not provided
        String rawPassword = request.getPassword();
        if (rawPassword == null || rawPassword.isBlank()) {
            rawPassword = UUID.randomUUID().toString();
        }
        // Hash password
        String hashed = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode(rawPassword);

        User newUser = User.builder()
                .email(request.getEmail())
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .avatar(request.getAvatar())
                .role(newRole)
                .status(User.UserStatus.ACTIVE)
                .passwordHash(hashed)
                .build();
        newUser.setTenantId(tenantId);
        User saved = userRepository.save(newUser);
        String subdomain = resolveSubdomain(saved.getTenantId());
        return mapToProfileResponse(saved, subdomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserProfileResponse> listUsers(Long callerUserId, String callerRole,
                                                       String keyword, String status, String role,
                                                       int page, int size, String sortBy, String sortDir) {
        Long tenantId = null;
        if (!"SUPER_ADMIN".equalsIgnoreCase(callerRole)) {
            User caller = userRepository.findById(callerUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("Caller user not found"));
            tenantId = caller.getTenantId();
        }

        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by(
                sortBy != null && !sortBy.isBlank() ? sortBy : "createdAt");
        sort = "desc".equalsIgnoreCase(sortDir) ? sort.descending() : sort.ascending();
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, sort);

        org.springframework.data.domain.Page<User> userPage = userRepository.searchUsers(
                tenantId, keyword, status, role, pageable);

        java.util.List<UserProfileResponse> content = userPage
                .map(u -> mapToProfileResponse(u, resolveSubdomain(u.getTenantId())))
                .getContent();

        return PageResponse.<UserProfileResponse>builder()
                .content(content)
                .pageNumber(userPage.getNumber())
                .pageSize(userPage.getSize())
                .totalElements(userPage.getTotalElements())
                .totalPages(userPage.getTotalPages())
                .first(userPage.isFirst())
                .last(userPage.isLast())
                .hasNext(userPage.hasNext())
                .hasPrevious(userPage.hasPrevious())
                .build();
    }

    @Override
    @Transactional
    public UserProfileResponse adminUpdateUser(Long callerUserId, String callerRole, Long targetUserId,
                                               AdminUpdateUserRequest request) {
        User caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Caller user not found"));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Target user not found"));

        // Tenant check
        if (!"SUPER_ADMIN".equalsIgnoreCase(callerRole) &&
                !Objects.equals(caller.getTenantId(), target.getTenantId())) {
            throw new BusinessException("Không có quyền cập nhật người dùng này", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        if (request.getFullName() != null) {
            target.setFullName(request.getFullName().trim());
        }
        if (request.getPhone() != null) {
            target.setPhone(request.getPhone().trim());
        }
        if (request.getAvatar() != null) {
            target.setAvatar(request.getAvatar().trim());
        }
        if (request.getRole() != null) {
            User.UserRole newRole = User.UserRole.valueOf(request.getRole());
            if (newRole == User.UserRole.SUPER_ADMIN) {
                throw new BusinessException("Không được cấp SUPER_ADMIN", HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
            target.setRole(newRole);
        }

        User saved = userRepository.save(target);
        String subdomain = resolveSubdomain(saved.getTenantId());
        return mapToProfileResponse(saved, subdomain);
    }

    @Override
    @Transactional
    public UserProfileResponse updateUserStatus(Long callerUserId, String callerRole, Long targetUserId,
                                                UpdateUserStatusRequest request) {
        User caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Caller user not found"));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Target user not found"));

        if (!"SUPER_ADMIN".equalsIgnoreCase(callerRole) &&
                !Objects.equals(caller.getTenantId(), target.getTenantId())) {
            throw new BusinessException("Không có quyền thay đổi trạng thái người dùng này", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        User.UserStatus newStatus;
        try {
            newStatus = User.UserStatus.valueOf(request.getStatus());
        } catch (Exception e) {
            throw new BusinessException("Trạng thái không hợp lệ", HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
        target.setStatus(newStatus);
        User saved = userRepository.save(target);

        if (newStatus == User.UserStatus.DISABLED) {
            // revoke refresh tokens from Redis
            String pattern = AuthServiceImpl.REDIS_PREFIX_USER_REFRESH + targetUserId + "*";
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.info("Revoked {} refresh token(s) for disabled user ID {}", keys.size(), targetUserId);
            } else {
                log.info("No refresh tokens found to revoke for disabled user ID {}", targetUserId);
            }
        }

        String subdomain = resolveSubdomain(saved.getTenantId());
        return mapToProfileResponse(saved, subdomain);
    }

    @Override
    @Transactional
    public UserProfileResponse assignRoles(Long callerUserId, String callerRole, Long targetUserId, AssignRolesRequest request) {
        User caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Caller user not found"));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Target user not found"));

        // Tenant check
        if (!"SUPER_ADMIN".equalsIgnoreCase(callerRole) &&
                !Objects.equals(caller.getTenantId(), target.getTenantId())) {
            throw new BusinessException("Không có quyền gán role cho người dùng này", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        // Self-demotion check: admin cannot remove own ADMIN role
        if (callerUserId.equals(targetUserId) && !caller.getRole().name().equals(User.UserRole.SUPER_ADMIN.name())) {
            // If trying to assign a role that does not include current ADMIN
            if (request.getRoles() == null || request.getRoles().stream().noneMatch(r -> r.equalsIgnoreCase(caller.getRole().name()))) {
                throw new BusinessException("Không được tự hạ cấp role ADMIN của chính mình", HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
        }

        // Validate roles list
        if (request.getRoles() == null || request.getRoles().isEmpty()) {
            throw new BusinessException("Danh sách role không được để trống", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }
        // For now, only support single role assignment
        if (request.getRoles().size() != 1) {
            throw new BusinessException("Chỉ hỗ trợ gán một role duy nhất cho người dùng", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }
        String roleStr = request.getRoles().get(0);
        User.UserRole newRole;
        try {
            newRole = User.UserRole.valueOf(roleStr);
        } catch (Exception e) {
            throw new BusinessException("Role không hợp lệ", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }
        if (newRole == User.UserRole.SUPER_ADMIN) {
            throw new BusinessException("Không được gán role SUPER_ADMIN", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        target.setRole(newRole);
        User saved = userRepository.save(target);

        // Revoke refresh tokens from Redis for the user (role change requires new JWT)
        String pattern = AuthServiceImpl.REDIS_PREFIX_USER_REFRESH + targetUserId + "*";
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("Revoked {} refresh token(s) for role-changed user ID {}", keys.size(), targetUserId);
        } else {
            log.info("No refresh tokens found to revoke for role-changed user ID {}", targetUserId);
        }

        String subdomain = resolveSubdomain(saved.getTenantId());
        return mapToProfileResponse(saved, subdomain);
    }
}
