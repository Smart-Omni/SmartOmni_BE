package com.smartomni.integration.repository;

import com.smartomni.integration.entity.MarketplaceConnection;
import com.smartomni.integration.entity.MarketplaceConnection.ConnectionStatus;
import com.smartomni.integration.entity.MarketplaceConnection.Platform;
import com.smartomni.integration.service.tiktok.TiktokConnectionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MarketplaceConnectionRepository: tra connection TikTok theo định danh shop")
class MarketplaceConnectionRepositoryTiktokLookupTest {

    private static final String SHOP = "7494000000000000003";

    private MarketplaceConnectionRepository repository;
    private MarketplaceConnection disconnected;
    private MarketplaceConnection connected;

    @BeforeEach
    void setUp() {
        repository = mock(MarketplaceConnectionRepository.class);
        when(repository.findTiktokByShopIdentifier(any())).thenCallRealMethod();
        when(repository.findTiktokByShopCipher(any())).thenCallRealMethod();
        disconnected = connection(1L, 1L, ConnectionStatus.DISCONNECTED);
        connected = connection(2L, 3L, ConnectionStatus.CONNECTED);
    }

    private static MarketplaceConnection connection(long id, long tenantId, ConnectionStatus status) {
        MarketplaceConnection c = new MarketplaceConnection();
        c.setId(id);
        c.setTenantId(tenantId);
        c.setPlatform(Platform.TIKTOK_SHOP);
        c.setStatus(status);
        c.setShopId(SHOP);
        return c;
    }

    @Test
    @DisplayName("Dòng chưa disconnect thắng, kể cả khi chỉ khớp shop_id còn dòng cũ khớp cipher")
    void activeRowWinsAcrossIdentifiers() {
        when(repository.findFirstByShopCipher(SHOP)).thenReturn(Optional.of(disconnected));
        when(repository.findFirstByPlatformAndShopIdAndStatusNotOrderByIdAsc(Platform.TIKTOK_SHOP, SHOP,
                ConnectionStatus.DISCONNECTED)).thenReturn(Optional.of(connected));

        assertEquals(Optional.of(connected), repository.findTiktokByShopIdentifier(SHOP));
    }

    @Test
    @DisplayName("Chỉ có dòng đã disconnect: giữ hành vi cũ (cipher, shop_id, seller_open_id)")
    void fallsBackToEarlierLookupWhenNoActiveRow() {
        when(repository.findFirstByShopId(SHOP)).thenReturn(Optional.of(disconnected));

        assertEquals(Optional.of(disconnected), repository.findTiktokByShopIdentifier(SHOP));
    }

    @Test
    @DisplayName("Không khớp dòng nào, hoặc định danh trống: rỗng")
    void emptyWhenNothingMatches() {
        assertTrue(repository.findTiktokByShopIdentifier(SHOP).isEmpty());
        assertTrue(repository.findTiktokByShopIdentifier(" ").isEmpty());
        assertTrue(repository.findTiktokByShopCipher(null).isEmpty());
        verify(repository, never()).findFirstByShopCipher(" ");
    }

    @Test
    @DisplayName("findTiktokByShopCipher (Open API): dòng chưa disconnect thắng, không thì như cũ")
    void cipherLookupPrefersActiveRow() {
        when(repository.findFirstByShopCipher("CIPHER")).thenReturn(Optional.of(disconnected));
        assertEquals(Optional.of(disconnected), repository.findTiktokByShopCipher("CIPHER"));

        when(repository.findFirstByPlatformAndShopCipherAndStatusNotOrderByIdAsc(Platform.TIKTOK_SHOP, "CIPHER",
                ConnectionStatus.DISCONNECTED)).thenReturn(Optional.of(connected));
        assertEquals(Optional.of(connected), repository.findTiktokByShopCipher("CIPHER"));
    }

    @Test
    @DisplayName("TiktokConnectionStore.findByShopIdentifier (markRevoked theo shop) dùng cùng thứ tự")
    void storeUsesTheSameLookup() {
        when(repository.findFirstByShopCipher(SHOP)).thenReturn(Optional.of(disconnected));
        when(repository.findFirstByPlatformAndShopCipherAndStatusNotOrderByIdAsc(Platform.TIKTOK_SHOP, SHOP,
                ConnectionStatus.DISCONNECTED)).thenReturn(Optional.of(connected));

        TiktokConnectionStore store = new TiktokConnectionStore(repository, null);

        assertEquals(Optional.of(connected), store.findByShopIdentifier(SHOP));
    }
}
