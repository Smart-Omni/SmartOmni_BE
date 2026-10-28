package com.smartomni.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO yêu cầu thay đổi trạng thái tài khoản (ACTIVE / DISABLED).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserStatusRequest {

    /**
     * Trạng thái mới: "ACTIVE" hoặc "DISABLED".
     */
    @NotBlank(message = "Trạng thái không được để trống")
    private String status;
}

