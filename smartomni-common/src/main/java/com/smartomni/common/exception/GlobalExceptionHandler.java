package com.smartomni.common.exception;

import com.smartomni.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Xu ly exception tap trung cho toan bo REST API cua service.
 * Import class nay vao tung service bang @Import hoac de trong cung
 * component-scan package "com.smartomni" (cac Application class quet
 * "com.smartomni" se tu dong nhan duoc).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error(message, "VALIDATION_ERROR"));
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<Object>> handlePropertyReferenceException(PropertyReferenceException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Trường sắp xếp không hợp lệ: " + ex.getPropertyName(), "INVALID_SORT_PARAMETER"));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuthenticationException(org.springframework.security.core.AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Chưa đăng nhập hoặc phiên đã hết hạn", "UNAUTHORIZED"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGenericException(Exception ex) {
        log.error("Unhandled API exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Không thể xử lý yêu cầu. Vui lòng thử lại.", "INTERNAL_ERROR"));
    }
}
