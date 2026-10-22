package com.smartomni.common.idempotency;

import com.smartomni.common.constant.AppConstants;
import com.smartomni.common.exception.BusinessException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyAspect Unit Tests")
class IdempotencyAspectTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Idempotent idempotent;

    @InjectMocks
    private IdempotencyAspect idempotencyAspect;

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("Thành công: Header X-Idempotency-Key hợp lệ và chưa tồn tại trong Redis (first attempt)")
    void testHandleIdempotency_Success_FirstAttempt() throws Throwable {
        String key = "unique-order-uuid-123";
        request.addHeader(AppConstants.HEADER_IDEMPOTENCY_KEY, key);

        when(idempotent.keyPrefix()).thenReturn("idempotency:");
        when(idempotent.expireInSeconds()).thenReturn(120L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("idempotency:" + key, "PROCESSING", 120L, TimeUnit.SECONDS))
                .thenReturn(Boolean.TRUE);
        when(joinPoint.proceed()).thenReturn("result-success");

        Object result = idempotencyAspect.handleIdempotency(joinPoint, idempotent);

        assertEquals("result-success", result);
        verify(valueOperations).set("idempotency:" + key, "COMPLETED", 120L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Chặn request trùng lặp: Header X-Idempotency-Key đã tồn tại -> Ném BusinessException (HTTP 409 Conflict)")
    void testHandleIdempotency_DuplicateKey_ThrowsConflict() throws Throwable {
        String key = "duplicate-key-456";
        request.addHeader(AppConstants.HEADER_IDEMPOTENCY_KEY, key);

        when(idempotent.keyPrefix()).thenReturn("idempotency:");
        when(idempotent.expireInSeconds()).thenReturn(120L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("idempotency:" + key, "PROCESSING", 120L, TimeUnit.SECONDS))
                .thenReturn(Boolean.FALSE);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                idempotencyAspect.handleIdempotency(joinPoint, idempotent));

        assertEquals(HttpStatus.CONFLICT, ex.getHttpStatus());
        assertEquals("DUPLICATE_REQUEST", ex.getErrorCode());
        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("Ném lỗi HTTP 400 khi thiếu header X-Idempotency-Key và required = true")
    void testHandleIdempotency_MissingHeader_Required_ThrowsBadRequest() throws Throwable {
        when(idempotent.required()).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                idempotencyAspect.handleIdempotency(joinPoint, idempotent));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getErrorCode());
        verify(joinPoint, never()).proceed();
    }
}
