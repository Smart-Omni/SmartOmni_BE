package com.smartomni.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.auth.config.SecurityConfig;
import com.smartomni.auth.dto.UpdateProfileRequest;
import com.smartomni.auth.dto.UserProfileResponse;
import com.smartomni.auth.service.UserService;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.common.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private org.springframework.data.redis.core.ValueOperations<String, String> values;

    @BeforeEach
    void allowActiveMockUser() {
        org.mockito.Mockito.lenient().when(jwtTokenProvider.getTokenVersion(org.mockito.ArgumentMatchers.anyString())).thenReturn(0L);
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(values);
        org.mockito.Mockito.lenient().when(values.get(org.mockito.ArgumentMatchers.anyString())).thenReturn("ACTIVE:0");
    }

    @MockBean
    private org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void tenantAdminCanListUsers() throws Exception {
        String token = "tenant-admin-token";
        when(jwtTokenProvider.parseClaims(token)).thenReturn(org.mockito.Mockito.mock(io.jsonwebtoken.Claims.class));
        when(jwtTokenProvider.getUserId(token)).thenReturn(7L);
        when(jwtTokenProvider.getRole(token)).thenReturn("TENANT_ADMIN");

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(userService).listUsers(7L, "TENANT_ADMIN", null, null, null,
                0, 10, "createdAt", "desc");
    }

    @Test
    @DisplayName("GET /api/v1/users/me thành công khi có Bearer token hợp lệ")
    void testGetCurrentUser_Success() throws Exception {
        String token = "valid-profile-token";
        when(jwtTokenProvider.parseClaims(token)).thenReturn(org.mockito.Mockito.mock(io.jsonwebtoken.Claims.class));
        when(jwtTokenProvider.getUserId(token)).thenReturn(1L);
        when(jwtTokenProvider.getRole(token)).thenReturn("ADMIN");

        UserProfileResponse response = UserProfileResponse.builder()
                .id(1L)
                .email("user@smartshop.vn")
                .fullName("Nguyen Van A")
                .phone("0901234567")
                .avatar("https://smartomni.vn/avatar1.png")
                .role("ADMIN")
                .tenantId(100L)
                .build();

        when(userService.getCurrentUserProfile(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("user@smartshop.vn"))
                .andExpect(jsonPath("$.data.full_name").value("Nguyen Van A"))
                .andExpect(jsonPath("$.data.phone").value("0901234567"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    @DisplayName("GET /api/v1/users/me trả 401 UNAUTHORIZED khi không có Bearer token")
    void testGetCurrentUser_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("PUT /api/v1/users/me cập nhật profile thành công khi có Bearer token")
    void testUpdateProfile_Success() throws Exception {
        String token = "valid-profile-token";
        when(jwtTokenProvider.parseClaims(token)).thenReturn(org.mockito.Mockito.mock(io.jsonwebtoken.Claims.class));
        when(jwtTokenProvider.getUserId(token)).thenReturn(1L);
        when(jwtTokenProvider.getRole(token)).thenReturn("ADMIN");

        UpdateProfileRequest request = UpdateProfileRequest.builder()
                .fullName("Nguyen Van C")
                .phone("0911223344")
                .avatar("https://smartomni.vn/new.png")
                .build();

        UserProfileResponse response = UserProfileResponse.builder()
                .id(1L)
                .email("user@smartshop.vn")
                .fullName("Nguyen Van C")
                .phone("0911223344")
                .avatar("https://smartomni.vn/new.png")
                .role("ADMIN")
                .build();

        when(userService.updateCurrentUserProfile(eq(1L), any(UpdateProfileRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.full_name").value("Nguyen Van C"))
                .andExpect(jsonPath("$.data.phone").value("0911223344"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me cập nhật một phần profile thành công")
    void testPatchProfile_Success() throws Exception {
        String token = "valid-profile-token";
        when(jwtTokenProvider.parseClaims(token)).thenReturn(org.mockito.Mockito.mock(io.jsonwebtoken.Claims.class));
        when(jwtTokenProvider.getUserId(token)).thenReturn(1L);
        when(jwtTokenProvider.getRole(token)).thenReturn("ADMIN");

        UpdateProfileRequest request = UpdateProfileRequest.builder()
                .phone("0999999999")
                .build();

        UserProfileResponse response = UserProfileResponse.builder()
                .id(1L)
                .email("user@smartshop.vn")
                .fullName("Nguyen Van A")
                .phone("0999999999")
                .role("ADMIN")
                .build();

        when(userService.updateCurrentUserProfile(eq(1L), any(UpdateProfileRequest.class))).thenReturn(response);

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.phone").value("0999999999"));
    }
}

