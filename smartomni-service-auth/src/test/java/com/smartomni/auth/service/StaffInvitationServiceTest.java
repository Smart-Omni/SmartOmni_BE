package com.smartomni.auth.service;

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
import com.smartomni.auth.service.impl.StaffInvitationServiceImpl;
import com.smartomni.common.exception.BusinessException;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffInvitationServiceTest {

    @Mock
    private StaffInvitationRepository staffInvitationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantLookupRepository tenantLookupRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private EntityManager entityManager;

    private StaffInvitationServiceImpl service;

    private User staffUser;
    private TenantLookup sampleTenant;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                anyList(), anyString())).thenReturn(1L);

        service = new StaffInvitationServiceImpl(
                staffInvitationRepository,
                userRepository,
                tenantLookupRepository,
                jwtTokenProvider,
                stringRedisTemplate,
                entityManager);

        staffUser = User.builder()
                .email("staff@smartomni.vn")
                .passwordHash("hashed")
                .fullName("Staff User")
                .role(User.UserRole.STAFF)
                .status(User.UserStatus.ACTIVE)
                .build();
        staffUser.setId(20L);
        staffUser.setTenantId(null);

        sampleTenant = new TenantLookup(100L, "ABC Store", "abc", "active", null);
    }

    @Test
    @DisplayName("Tenant Admin gửi lời mời thành công -> 201 với thông tin cửa hàng")
    void createInvitation_Success() {
        CreateStaffInvitationRequest req = new CreateStaffInvitationRequest("staff@smartomni.vn", "STAFF");
        when(userRepository.findByEmail("staff@smartomni.vn")).thenReturn(Optional.of(staffUser));
        when(staffInvitationRepository.existsByTenantIdAndInvitedEmailIgnoreCaseAndStatus(
                100L, "staff@smartomni.vn", InvitationStatus.PENDING)).thenReturn(false);
        when(tenantLookupRepository.findById(100L)).thenReturn(Optional.of(sampleTenant));
        when(staffInvitationRepository.save(any(StaffInvitation.class))).thenAnswer(inv -> {
            StaffInvitation s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        StaffInvitationResponse res = service.createInvitation(1L, 100L, req);

        assertEquals(1L, res.getId());
        assertEquals(100L, res.getTenantId());
        assertEquals("ABC Store", res.getCompanyName());
        assertEquals("abc", res.getSubdomain());
        assertEquals("STAFF", res.getRole());
        assertEquals("PENDING", res.getStatus());
    }

    @Test
    @DisplayName("Gửi lời mời thất bại 404 USER_NOT_FOUND khi email chưa đăng ký")
    void createInvitation_UserNotFound() {
        CreateStaffInvitationRequest req = new CreateStaffInvitationRequest("missing@smartomni.vn", "STAFF");
        when(userRepository.findByEmail("missing@smartomni.vn")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInvitation(1L, 100L, req));
        assertEquals(HttpStatus.NOT_FOUND, ex.getHttpStatus());
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("Gửi lời mời thất bại 400 USER_ALREADY_IN_TENANT khi user đã thuộc cửa hàng khác")
    void createInvitation_UserAlreadyInTenant() {
        staffUser.setTenantId(200L);
        CreateStaffInvitationRequest req = new CreateStaffInvitationRequest("staff@smartomni.vn", "STAFF");
        when(userRepository.findByEmail("staff@smartomni.vn")).thenReturn(Optional.of(staffUser));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInvitation(1L, 100L, req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("USER_ALREADY_IN_TENANT", ex.getErrorCode());
    }

    @Test
    @DisplayName("Gửi lời mời thất bại 409 INVITATION_ALREADY_PENDING khi đang có lời mời PENDING")
    void createInvitation_AlreadyPending() {
        CreateStaffInvitationRequest req = new CreateStaffInvitationRequest("staff@smartomni.vn", "STAFF");
        when(userRepository.findByEmail("staff@smartomni.vn")).thenReturn(Optional.of(staffUser));
        when(staffInvitationRepository.existsByTenantIdAndInvitedEmailIgnoreCaseAndStatus(
                100L, "staff@smartomni.vn", InvitationStatus.PENDING)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createInvitation(1L, 100L, req));
        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        assertEquals("INVITATION_ALREADY_PENDING", ex.getErrorCode());
    }

    @Test
    @DisplayName("Staff chấp nhận lời mời -> cập nhật tenantId, hủy lời mời khác và cấp AuthResponse mới")
    void acceptInvitation_Success_IssuesNewTokenWithTenant() {
        StaffInvitation inv = StaffInvitation.builder()
                .invitedEmail("staff@smartomni.vn")
                .invitedUserId(20L)
                .role("MANAGER")
                .status(InvitationStatus.PENDING)
                .invitedBy(1L)
                .expiresAt(Instant.now().plus(3, ChronoUnit.DAYS))
                .build();
        inv.setId(50L);
        inv.setTenantId(100L);

        StaffInvitation other = StaffInvitation.builder()
                .invitedEmail("staff@smartomni.vn")
                .invitedUserId(20L)
                .role("STAFF")
                .status(InvitationStatus.PENDING)
                .invitedBy(2L)
                .expiresAt(Instant.now().plus(3, ChronoUnit.DAYS))
                .build();
        other.setId(51L);
        other.setTenantId(101L);

        when(userRepository.findById(20L)).thenReturn(Optional.of(staffUser));
        when(staffInvitationRepository.findByIdForInvitee(50L, 20L, "staff@smartomni.vn"))
                .thenReturn(Optional.of(inv));
        when(tenantLookupRepository.findById(100L)).thenReturn(Optional.of(sampleTenant));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(staffInvitationRepository.save(any(StaffInvitation.class))).thenAnswer(i -> i.getArgument(0));
        when(staffInvitationRepository.findOtherPendingForInvitee(
                50L, 20L, "staff@smartomni.vn", InvitationStatus.PENDING))
                .thenReturn(List.of(other));
        when(jwtTokenProvider.generateToken(20L, "MANAGER", 100L, "abc", 0L))
                .thenReturn("new.jwt.token.with.tenant");

        AuthResponse response = service.acceptInvitation(20L, 50L);

        assertEquals("new.jwt.token.with.tenant", response.getAccessToken());
        assertEquals(100L, response.getUserInfo().getTenantId());
        assertEquals("abc", response.getUserInfo().getSubdomain());
        assertEquals("MANAGER", response.getUserInfo().getRole());
        assertEquals(InvitationStatus.ACCEPTED, inv.getStatus());
        assertEquals(InvitationStatus.CANCELLED, other.getStatus());
    }

    @Test
    @DisplayName("Staff từ chối lời mời -> cập nhật status = DECLINED")
    void declineInvitation_Success() {
        StaffInvitation inv = StaffInvitation.builder()
                .invitedEmail("staff@smartomni.vn")
                .invitedUserId(20L)
                .role("STAFF")
                .status(InvitationStatus.PENDING)
                .invitedBy(1L)
                .expiresAt(Instant.now().plus(3, ChronoUnit.DAYS))
                .build();
        inv.setId(50L);
        inv.setTenantId(100L);

        when(userRepository.findById(20L)).thenReturn(Optional.of(staffUser));
        when(staffInvitationRepository.findByIdForInvitee(50L, 20L, "staff@smartomni.vn"))
                .thenReturn(Optional.of(inv));
        when(staffInvitationRepository.save(any(StaffInvitation.class))).thenAnswer(i -> i.getArgument(0));
        when(tenantLookupRepository.findById(100L)).thenReturn(Optional.of(sampleTenant));

        StaffInvitationResponse res = service.declineInvitation(20L, 50L);

        assertEquals("DECLINED", res.getStatus());
        assertNotNull(res.getRespondedAt());
    }
}
