package com.smartomni.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartomni.common.dto.ApiResponse;
import com.smartomni.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

final class AuthenticationErrorWriter {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private AuthenticationErrorWriter() {
    }

    static void write(HttpServletResponse response, BusinessException error) throws IOException {
        response.setStatus(error.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        MAPPER.writeValue(response.getWriter(), ApiResponse.error(error.getMessage(), error.getErrorCode()));
    }
}
