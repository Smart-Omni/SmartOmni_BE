package com.smartomni.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for Tenant Onboarding (UC-17, TEN-01.1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantOnboardingRequest {

    private String companyName;
    private String businessName;

    @NotBlank(message = "Subdomain không được để trống")
    @Pattern(regexp = "^[a-z0-9-]{3,30}$", message = "Subdomain chỉ gồm chữ thường, số và dấu gạch ngang, 3-30 ký tự")
    private String subdomain;

    @NotBlank(message = "Email quản trị viên không được để trống")
    @Email(message = "Email quản trị viên không đúng định dạng")
    private String adminEmail;

    private String adminFullName;

    private String adminPassword;

    @Email(message = "Email liên hệ không đúng định dạng")
    private String contactEmail;

    private String contactPhone;

    @Builder.Default
    private String planType = "FREE";
    private String initialPlanName;

    public String getCompanyName() {
        if (companyName != null && !companyName.isBlank()) {
            return companyName;
        }
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
        if (this.companyName == null) {
            this.companyName = businessName;
        }
    }

    public String getAdminEmail() {
        if (adminEmail != null && !adminEmail.isBlank()) {
            return adminEmail;
        }
        return contactEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
        if (this.contactEmail == null) {
            this.contactEmail = adminEmail;
        }
    }

    public String getContactEmail() {
        if (contactEmail != null && !contactEmail.isBlank()) {
            return contactEmail;
        }
        return adminEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
        if (this.adminEmail == null) {
            this.adminEmail = contactEmail;
        }
    }

    public String getPlanType() {
        if (planType != null && !planType.isBlank()) {
            return planType;
        }
        return initialPlanName != null ? initialPlanName : "FREE";
    }

    public void setInitialPlanName(String initialPlanName) {
        this.initialPlanName = initialPlanName;
        if (this.planType == null) {
            this.planType = initialPlanName;
        }
    }
}
