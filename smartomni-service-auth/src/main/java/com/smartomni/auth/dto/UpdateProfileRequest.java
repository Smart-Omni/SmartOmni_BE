package com.smartomni.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @Size(max = 100, message = "Họ và tên không được vượt quá 100 ký tự")
    @com.fasterxml.jackson.annotation.JsonAlias({"fullName", "full_name"})
    @com.fasterxml.jackson.annotation.JsonProperty("full_name")
    private String fullName;

    @Size(max = 50, message = "Số điện thoại không được vượt quá 50 ký tự")
    private String phone;

    @Size(max = 500, message = "Đường dẫn ảnh đại diện không được vượt quá 500 ký tự")
    private String avatar;
}
