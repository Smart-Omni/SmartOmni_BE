package com.smartomni.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @NotBlank(message = "Token đặt lại mật khẩu không được để trống")
    private String token;

    @NotBlank(message = "Mật khẩu mới không được để trống")
    @Size(min = 6, max = 100, message = "Mật khẩu mới phải từ 6 đến 100 ký tự")
    @com.fasterxml.jackson.annotation.JsonAlias({"newPassword", "new_password"})
    @com.fasterxml.jackson.annotation.JsonProperty("new_password")
    private String newPassword;

    @com.fasterxml.jackson.annotation.JsonAlias({"confirmPassword", "confirm_password"})
    @com.fasterxml.jackson.annotation.JsonProperty("confirm_password")
    private String confirmPassword;
}
