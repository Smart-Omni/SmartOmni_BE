package com.smartomni.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "Mật khẩu hiện tại không được để trống")
    @com.fasterxml.jackson.annotation.JsonAlias({"currentPassword", "current_password"})
    @com.fasterxml.jackson.annotation.JsonProperty("current_password")
    private String currentPassword;

    @NotBlank(message = "Mật khẩu mới không được để trống")
    @Size(min = 6, max = 100, message = "Mật khẩu mới phải từ 6 đến 100 ký tự")
    @com.fasterxml.jackson.annotation.JsonAlias({"newPassword", "new_password"})
    @com.fasterxml.jackson.annotation.JsonProperty("new_password")
    private String newPassword;

    @NotBlank(message = "Xác nhận mật khẩu mới không được để trống")
    @com.fasterxml.jackson.annotation.JsonAlias({"confirmPassword", "confirm_password"})
    @com.fasterxml.jackson.annotation.JsonProperty("confirm_password")
    private String confirmPassword;
}
