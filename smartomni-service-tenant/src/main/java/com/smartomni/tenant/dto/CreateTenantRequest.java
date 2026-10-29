
package com.smartomni.tenant.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Request payload for creating / onboarding a tenant (TEN-01.1, UC-17).
 */
@Getter
@Setter
@ToString(callSuper = true)
public class CreateTenantRequest extends TenantOnboardingRequest {
}
