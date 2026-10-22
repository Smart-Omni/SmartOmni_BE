package com.smartomni.common.exception;

import org.springframework.http.HttpStatus;

public class TokenExpiredException extends BusinessException {

    public TokenExpiredException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
    }
}
