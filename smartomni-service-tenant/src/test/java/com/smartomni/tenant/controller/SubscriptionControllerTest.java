package com.smartomni.tenant.controller;

import com.smartomni.common.exception.GlobalExceptionHandler;
import com.smartomni.common.security.JwtTokenProvider;
import com.smartomni.common.tenant.TenantContext;
import com.smartomni.tenant.dto.SubscriptionCheckoutResponse;
import com.smartomni.tenant.dto.SubscriptionInvoiceDto;
import com.smartomni.tenant.dto.SubscriptionOrderStatusResponse;
import com.smartomni.tenant.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("SubscriptionController WebMvc Tests")
class SubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubscriptionService subscriptionService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(10L);
        TenantContext.setCurrentUserId(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("checkout: Tạo phiên thanh toán nâng cấp PRO thành công -> 200 OK với thông tin QR")
    void checkout_success() throws Exception {
        SubscriptionCheckoutResponse response = SubscriptionCheckoutResponse.builder()
                .orderCode("PRO001000201")
                .amount(new BigDecimal("499000.00"))
                .qrUrl("https://vietqr.app/img?acc=123&bank=MB&amount=499000&des=PRO001000201")
                .planName("PRO")
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();

        when(subscriptionService.createCheckoutSession(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/tenants/subscription/checkout")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value("PRO001000201"))
                .andExpect(jsonPath("$.amount").value(499000.00))
                .andExpect(jsonPath("$.planName").value("PRO"));
    }

    @Test
    @DisplayName("getOrderStatus: Tra cứu trạng thái đơn hàng -> 200 OK")
    void getOrderStatus_success() throws Exception {
        SubscriptionOrderStatusResponse response = SubscriptionOrderStatusResponse.builder()
                .orderCode("PRO001000201")
                .status("PAID")
                .planName("PRO")
                .paidAt(Instant.now())
                .build();

        when(subscriptionService.getOrderStatus(any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/tenants/subscription/orders/PRO001000201/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.planName").value("PRO"));
    }

    @Test
    @DisplayName("getInvoices: Lấy danh sách hóa đơn của tenant -> 200 OK")
    void getInvoices_success() throws Exception {
        SubscriptionInvoiceDto invoice = SubscriptionInvoiceDto.builder()
                .id(101L)
                .amount(new BigDecimal("499000.00"))
                .status("PAID")
                .build();

        when(subscriptionService.getTenantInvoices(10L)).thenReturn(List.of(invoice));

        mockMvc.perform(get("/api/v1/tenants/subscription/invoices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].status").value("PAID"));
    }
}
