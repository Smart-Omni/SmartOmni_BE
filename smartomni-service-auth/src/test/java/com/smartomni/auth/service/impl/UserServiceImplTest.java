package com.smartomni.auth.service.impl;

import com.smartomni.auth.dto.AdminUpdateUserRequest;
import com.smartomni.auth.dto.AssignRolesRequest;
import com.smartomni.auth.dto.CreateUserRequest;
import com.smartomni.auth.dto.UpdateUserStatusRequest;
import com.smartomni.auth.entity.User;
import com.smartomni.auth.repository.TenantLookupRepository;
import com.smartomni.auth.repository.UserRepository;
import com.smartomni.common.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantLookupRepository tenantLookupRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> values;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private UserServiceImpl userService;

    private User caller;
    private User target;

    @BeforeEach
    void setUp() {
        caller = User.builder()
                .email("admin@example.com")
                .role(User.UserRole.ADMIN)
                .status(User.UserStatus.ACTIVE)
                .build();
        caller.setId(1L);
        caller.setTenantId(100L);

        target = User.builder()
                .email("user@example.com")
                .role(User.UserRole.MANAGER)
                .status(User.UserStatus.ACTIVE)
                .build();
        target.setId(2L);
        target.setTenantId(100L);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(values);
    }

    @Test
    void createUser_successful() {
        CreateUserRequest req = new CreateUserRequest();
        req.setEmail("new@example.com");
        req.setFullName("New User");
        req.setPassword("pass123");
        req.setRole(User.UserRole.MANAGER.name());

        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        when(userRepository.findByEmailAndTenantId(req.getEmail(), caller.getTenantId()))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(3L);
            return u;
        });

        var response = userService.createUser(caller.getId(), caller.getRole().name(), req);
        assertNotNull(response);
        assertEquals("new@example.com", response.getEmail());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void createUser_duplicateEmail_throws() {
        CreateUserRequest req = new CreateUserRequest();
        req.setEmail("dup@example.com");
        req.setRole(User.UserRole.MANAGER.name());
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        when(userRepository.findByEmailAndTenantId(req.getEmail(), caller.getTenantId()))
                .thenReturn(Optional.of(target));
        assertThrows(BusinessException.class, () ->
                userService.createUser(caller.getId(), caller.getRole().name(), req));
    }

    @Test
    void updateUserStatus_disable_revokesTokens() {
        UpdateUserStatusRequest statusReq = new UpdateUserStatusRequest();
        statusReq.setStatus(User.UserStatus.DISABLED.name());
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        when(userRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(values.get("auth:user_refresh:2")).thenReturn("someToken");

        var resp = userService.updateUserStatus(caller.getId(), caller.getRole().name(), target.getId(), statusReq);
        assertEquals(User.UserStatus.DISABLED.name(), resp.getStatus());
        assertEquals(1L, target.getTokenVersion());
        verify(values).set("auth:user_access:2", "DISABLED:1");
        // Verify revocation
        verify(stringRedisTemplate).delete("auth:refresh_token:someToken");
        verify(stringRedisTemplate).delete("auth:user_refresh:2");
        verify(stringRedisTemplate, never()).keys(anyString());
    }

    @Test
    void assignRoles_successful() {
        AssignRolesRequest req = new AssignRolesRequest();
        req.setRoles(Collections.singletonList(User.UserRole.MANAGER.name()));
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        when(userRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(values.get("auth:user_refresh:2")).thenReturn("token");
        var resp = userService.assignRoles(caller.getId(), caller.getRole().name(), target.getId(), req);
        assertEquals(User.UserRole.MANAGER.name(), resp.getRole());
        assertEquals(1L, target.getTokenVersion());
        verify(values).set("auth:user_access:2", "ACTIVE:1");
        verify(stringRedisTemplate).delete("auth:refresh_token:token");
        verify(stringRedisTemplate).delete("auth:user_refresh:2");
        verify(stringRedisTemplate, never()).keys(anyString());
    }

    @Test
    void assignRoles_invalidRole_throws() {
        AssignRolesRequest req = new AssignRolesRequest();
        req.setRoles(Collections.singletonList("INVALID_ROLE"));
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        when(userRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
        assertThrows(BusinessException.class, () ->
                userService.assignRoles(caller.getId(), caller.getRole().name(), target.getId(), req));
    }

    @Test
    void assignRoles_selfDemotion_forbidden() {
        // caller is ADMIN, trying to assign role CUSTOMER to self -> should be forbidden because removing ADMIN
        AssignRolesRequest req = new AssignRolesRequest();
        req.setRoles(Collections.singletonList(User.UserRole.CUSTOMER.name()));
        // caller and target same
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        when(userRepository.findByIdForUpdate(caller.getId())).thenReturn(Optional.of(caller));
        assertThrows(BusinessException.class, () ->
                userService.assignRoles(caller.getId(), caller.getRole().name(), caller.getId(), req));
    }

    @Test
    void assignRoles_wrongTenant_forbidden() {
        // caller tenant 100, target tenant 200
        User otherTarget = User.builder()
                .email("other@example.com")
                .role(User.UserRole.MANAGER)
                .status(User.UserStatus.ACTIVE)
                .build();
        otherTarget.setId(3L);
        otherTarget.setTenantId(200L);

        AssignRolesRequest req = new AssignRolesRequest();
        req.setRoles(Collections.singletonList(User.UserRole.MANAGER.name()));
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        when(userRepository.findByIdForUpdate(otherTarget.getId())).thenReturn(Optional.of(otherTarget));
        assertThrows(BusinessException.class, () ->
                userService.assignRoles(caller.getId(), caller.getRole().name(), otherTarget.getId(), req));
    }

    @Test
    void removedStaffRoleIsRejectedWithInvalidRoleEverywhere() {
        lenient().when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        lenient().when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        lenient().when(userRepository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));

        CreateUserRequest create = new CreateUserRequest();
        create.setEmail("m@example.com");
        create.setRole("STAFF");
        AdminUpdateUserRequest update = new AdminUpdateUserRequest();
        update.setRole("STAFF");
        AssignRolesRequest assign = new AssignRolesRequest();
        assign.setRoles(Collections.singletonList("STAFF"));

        assertInvalidRole(() -> userService.createUser(caller.getId(), "ADMIN", create));
        assertInvalidRole(() -> userService.adminUpdateUser(caller.getId(), "ADMIN", target.getId(), update));
        assertInvalidRole(() -> userService.assignRoles(caller.getId(), "ADMIN", target.getId(), assign));
        assertInvalidRole(() -> userService.listUsers(caller.getId(), "SUPER_ADMIN", null, null, "STAFF", 0, 10, null, null));

        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).searchUsers(any(), any(), any(), any(), any());
        assertEquals(User.UserRole.MANAGER, target.getRole());
    }

    @Test
    void listUsersFiltersByManagerRole() {
        when(userRepository.searchUsers(isNull(), isNull(), isNull(), eq("MANAGER"), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        var page = userService.listUsers(caller.getId(), "SUPER_ADMIN", null, null, "manager", 0, 10, null, null);

        assertEquals(0, page.getContent().size());
        verify(userRepository).searchUsers(isNull(), isNull(), isNull(), eq("MANAGER"), any());
    }

    private static void assertInvalidRole(org.junit.jupiter.api.function.Executable call) {
        BusinessException error = assertThrows(BusinessException.class, call);
        assertEquals("INVALID_ROLE", error.getErrorCode());
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, error.getHttpStatus());
    }
}
