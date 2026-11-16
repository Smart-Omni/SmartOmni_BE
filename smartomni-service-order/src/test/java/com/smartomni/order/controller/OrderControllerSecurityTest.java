package com.smartomni.order.controller;

import com.smartomni.common.dto.CreateManualOrderRequest;
import com.smartomni.common.dto.OrderDetailDto;
import com.smartomni.order.entity.Order;
import com.smartomni.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real @PreAuthorize annotations; plain controller unit tests cannot catch B9. */
@SpringJUnitWebConfig(OrderControllerSecurityTest.TestConfig.class)
@DisplayName("OrderController method security (B9)")
class OrderControllerSecurityTest {

    private static final String CREATE_BODY = """
            {"platform":"SHOPEE","customerName":"A","items":[{"skuId":10,"quantity":1,"unitPrice":1000}]}""";

    @Configuration
    @EnableWebMvc
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestConfig {
        @Bean OrderService orderService() { return mock(OrderService.class); }
        @Bean OrderController orderController(OrderService orderService) { return new OrderController(orderService); }
    }

    private final OrderService orderService;
    private final MockMvc mockMvc;

    OrderControllerSecurityTest(@Autowired WebApplicationContext context, @Autowired OrderService orderService) {
        this.orderService = orderService;
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @BeforeEach
    void resetMock() {
        reset(orderService);
        when(orderService.updateStatus(1L, Order.OrderStatus.TO_SHIP)).thenReturn(new Order());
        when(orderService.createManualOrder(any(CreateManualOrderRequest.class))).thenReturn(OrderDetailDto.builder().id(5L).build());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                9L, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void staffCanMovePendingOrderToShip() throws Exception {
        loginAs("STAFF");
        mockMvc.perform(patch("/api/v1/orders/1/status").param("status", "TO_SHIP")).andExpect(status().isOk());
        verify(orderService).updateStatus(1L, Order.OrderStatus.TO_SHIP);
    }

    @Test
    void staffCanCreateManualOrder() throws Exception {
        loginAs("STAFF");
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk());
        verify(orderService).createManualOrder(any(CreateManualOrderRequest.class));
    }

    @Test
    void tenantAdminAndAdminKeepAccess() throws Exception {
        for (String role : new String[]{"TENANT_ADMIN", "ADMIN"}) {
            loginAs(role);
            mockMvc.perform(patch("/api/v1/orders/1/status").param("status", "TO_SHIP")).andExpect(status().isOk());
        }
    }

    @Test
    void customerAndLegacyManagerAreDenied() {
        for (String role : new String[]{"CUSTOMER", "MANAGER"}) {
            loginAs(role);
            var patchError = assertThrows(Exception.class,
                    () -> mockMvc.perform(patch("/api/v1/orders/1/status").param("status", "TO_SHIP")));
            assertInstanceOf(AccessDeniedException.class, patchError.getCause());
            var createError = assertThrows(Exception.class,
                    () -> mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)));
            assertInstanceOf(AccessDeniedException.class, createError.getCause());
        }
        verify(orderService, never()).updateStatus(any(), any());
        verify(orderService, never()).createManualOrder(any());
    }
}
