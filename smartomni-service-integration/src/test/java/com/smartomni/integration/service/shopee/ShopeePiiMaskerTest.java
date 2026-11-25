package com.smartomni.integration.service.shopee;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShopeePiiMaskerTest {

    @Test
    void masksPhoneInMiddleAndPreservesAlreadyMasked() {
        assertEquals("090****567", ShopeePiiMasker.maskPhone("0901234567"));
        assertEquals("******64", ShopeePiiMasker.maskPhone("******64"));
        assertEquals("****", ShopeePiiMasker.maskPhone("1234"));
        assertNull(ShopeePiiMasker.maskPhone(null));
    }

    @Test
    void masksStreetAddressAndKeepsDistrictCity() {
        assertEquals("******, District 1, HCMC", ShopeePiiMasker.maskAddress("123 Le Loi, District 1, HCMC"));
        assertEquals("******, Quận 3, TP.HCM", ShopeePiiMasker.maskAddress("****** Quận 3, TP.HCM".replace("****** ", "128 Nguyễn Đình Chiểu, ")));
        assertEquals("****", ShopeePiiMasker.maskAddress("Short"));
        assertEquals("****** HCMC", ShopeePiiMasker.maskAddress("****** HCMC"));
        assertNull(ShopeePiiMasker.maskAddress(null));
    }
}
