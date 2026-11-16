package com.smartomni.order.controller;

import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.dto.ReturnDetailDto;
import com.smartomni.common.dto.ReturnInspectionRequest;
import com.smartomni.order.dto.CreateReturnRequest;
import com.smartomni.order.entity.ReturnRefund;
import com.smartomni.order.service.ReturnRefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ReturnRefundController Unit Tests")
class ReturnRefundControllerTest {

    private ReturnRefundService service;
    private ReturnRefundController controller;

    @BeforeEach
    void setUp() {
        service = mock(ReturnRefundService.class);
        controller = new ReturnRefundController(service);
    }

    @Test
    @DisplayName("list returns paginated returns")
    void list_Success() {
        ReturnDetailDto dto = ReturnDetailDto.builder().id(1L).build();
        when(service.getReturns(any())).thenReturn(new PageImpl<>(List.of(dto)));

        ApiResponse<Page<ReturnDetailDto>> response = controller.list(PageRequest.of(0, 10));

        assertNotNull(response);
        assertEquals(1, response.getData().getTotalElements());
        verify(service).getReturns(any());
    }

    @Test
    @DisplayName("getDetail returns single return detail")
    void getDetail_Success() {
        ReturnDetailDto dto = ReturnDetailDto.builder().id(5L).build();
        when(service.getReturnDetail(5L)).thenReturn(dto);

        ApiResponse<ReturnDetailDto> response = controller.getDetail(5L);

        assertNotNull(response);
        assertEquals(5L, response.getData().getId());
        verify(service).getReturnDetail(5L);
    }

    @Test
    @DisplayName("inspect triggers performInspection")
    void inspect_Success() {
        ReturnInspectionRequest request = ReturnInspectionRequest.builder()
                .result(ReturnInspectionRequest.InspectionResult.MATCHED)
                .build();
        ReturnDetailDto dto = ReturnDetailDto.builder().id(5L).inspectionResult("MATCHED").build();
        when(service.performInspection(eq(5L), any())).thenReturn(dto);

        ApiResponse<ReturnDetailDto> response = controller.inspect(5L, request);

        assertNotNull(response);
        assertEquals("MATCHED", response.getData().getInspectionResult());
        verify(service).performInspection(5L, request);
    }

    @Test
    @DisplayName("create triggers createReturnRequest")
    void create_Success() {
        CreateReturnRequest request = new CreateReturnRequest();
        request.setOrderId(10L);
        request.setReason("Defective");
        ReturnRefund refund = new ReturnRefund();
        refund.setId(1L);
        when(service.createReturnRequest(request)).thenReturn(refund);

        ApiResponse<ReturnRefund> response = controller.create(request);

        assertNotNull(response);
        assertEquals(1L, response.getData().getId());
        verify(service).createReturnRequest(request);
    }

    @Test
    @DisplayName("approve triggers approveAndRestock")
    void approve_Success() {
        ReturnRefund refund = new ReturnRefund();
        refund.setId(1L);
        refund.setRestocked(true);
        when(service.approveAndRestock(1L)).thenReturn(refund);

        ApiResponse<ReturnRefund> response = controller.approve(1L);

        assertNotNull(response);
        assertTrue(response.getData().isRestocked());
        verify(service).approveAndRestock(1L);
    }
}
