package com.smartomni.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.auth.config.SecurityConfig;
import com.smartomni.auth.dto.AuthResponse;
import com.smartomni.auth.dto.LoginRequest;
import com.smartomni.auth.service.AuthService;
import com.smartomni.common.exception.BusinessException;
import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.common.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @MockBean
    private org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("POST /api/v1/auth/login thành công trả về HTTP 200 và JWT token")
    void testLogin_Success() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("Password123@")
                .subdomain("smartshop")
                .build();

        AuthResponse.UserInfo userInfo = AuthResponse.UserInfo.builder()
                .id(1L)
                .email("admin@smartshop.vn")
                .fullName("Nguyen Van A")
                .role("ADMIN")
                .tenantId(100L)
                .subdomain("smartshop")
                .build();

        AuthResponse authResponse = AuthResponse.builder()
                .accessToken("mock.jwt.token")
                .tokenType("Bearer")
                .expiresIn(86400L)
                .userInfo(userInfo)
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.access_token").value("mock.jwt.token"))
                .andExpect(jsonPath("$.data.token_type").value("Bearer"))
                .andExpect(jsonPath("$.data.expires_in").value(86400))
                .andExpect(jsonPath("$.data.user_info.email").value("admin@smartshop.vn"))
                .andExpect(jsonPath("$.data.user_info.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.user_info.tenant_id").value(100));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login validation thất bại khi email không đúng định dạng")
    void testLogin_ValidationFailure_InvalidEmail() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("invalid-email-format")
                .password("Password123@")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login trả về HTTP 401 khi sai mật khẩu")
    void testLogin_Unauthorized_WrongPassword() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("admin@smartshop.vn")
                .password("WrongPassword")
                .build();

        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException("Email hoặc mật khẩu không chính xác", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email hoặc mật khẩu không chính xác"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/logout trả về HTTP 200")
    void testLogout_Success() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/auth/internal/tenants/{tenantId}/evict trả về HTTP 200")
    void testEvictTenantSessions_Success() throws Exception {
        mockMvc.perform(post("/api/v1/auth/internal/tenants/100/evict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Đã thu hồi toàn bộ phiên của Tenant 100"));
    }
}
