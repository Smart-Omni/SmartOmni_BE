package com.smartomni.common.security;

import com.smartomni.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        AuthenticationErrorWriter.write(response,
                new BusinessException("Authentication required", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
    }
}
