package com.smartomni.auth.service;

import com.smartomni.auth.dto.UpdateProfileRequest;
import com.smartomni.auth.dto.UserProfileResponse;
import com.smartomni.auth.entity.User;
import com.smartomni.auth.repository.TenantLookupRepository;
import com.smartomni.auth.repository.TenantLookupRepository.TenantLookup;
import com.smartomni.auth.repository.UserRepository;
import com.smartomni.auth.service.impl.UserServiceImpl;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantLookupRepository tenantLookupRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private UserServiceImpl userService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepository, tenantLookupRepository, entityManager, stringRedisTemplate);

        sampleUser = User.builder()
                .email("user@smartshop.vn")
                .fullName("Nguyen Van A")
                .phone("0901234567")
                .avatar("https://smartomni.vn/avatar1.png")
                .role(User.UserRole.ADMIN)
                .status(User.UserStatus.ACTIVE)
                .build();
        sampleUser.setId(1L);
        sampleUser.setTenantId(100L);
    }

    @Test
    @DisplayName("Lấy profile cá nhân thành công")
    void testGetCurrentUserProfile_Success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(tenantLookupRepository.findById(100L)).thenReturn(Optional.of(new TenantLookup(100L, "Smart Shop", "smartshop", "active", null)));

        UserProfileResponse response = userService.getCurrentUserProfile(1L);

        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("user@smartshop.vn", response.getEmail());
        assertEquals("Nguyen Van A", response.getFullName());
        assertEquals("0901234567", response.getPhone());
        assertEquals("https://smartomni.vn/avatar1.png", response.getAvatar());
        assertEquals("ADMIN", response.getRole());
        assertEquals("smartshop", response.getSubdomain());
    }

    @Test
    @DisplayName("Lấy profile thất bại khi user ID null")
    void testGetCurrentUserProfile_NullUserId() {
        BusinessException ex = assertThrows(BusinessException.class, () -> userService.getCurrentUserProfile(null));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getHttpStatus());
    }

    @Test
    @DisplayName("Lấy profile thất bại khi không tìm thấy user")
    void testGetCurrentUserProfile_UserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getCurrentUserProfile(999L));
    }

    @Test
    @DisplayName("Cập nhật profile cá nhân thành công")
    void testUpdateCurrentUserProfile_Success() {
        UpdateProfileRequest request = UpdateProfileRequest.builder()
                .fullName("Nguyen Van B")
                .phone("0988888888")
                .avatar("https://smartomni.vn/new-avatar.png")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(sampleUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tenantLookupRepository.findById(100L)).thenReturn(Optional.of(new TenantLookup(100L, "Smart Shop", "smartshop", "active", null)));

        UserProfileResponse response = userService.updateCurrentUserProfile(1L, request);

        assertNotNull(response);
        assertEquals("Nguyen Van B", response.getFullName());
        assertEquals("0988888888", response.getPhone());
        assertEquals("https://smartomni.vn/new-avatar.png", response.getAvatar());
        verify(userRepository).save(sampleUser);
    }
}
