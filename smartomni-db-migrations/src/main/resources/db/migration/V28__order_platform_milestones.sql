-- Marketplace delivery milestones of an order (TikTok Get Order Detail: rts_time, collection_time, delivery_time,
-- cancel_time). Display and reconciliation only: they never drive the order status, the wallet or loyalty.
-- NULL until the milestone happens and for platforms that do not report it (Shopee for now).
ALTER TABLE orders ADD COLUMN platform_rts_at timestamptz;
ALTER TABLE orders ADD COLUMN platform_collected_at timestamptz;
ALTER TABLE orders ADD COLUMN platform_delivered_at timestamptz;
ALTER TABLE orders ADD COLUMN platform_cancelled_at timestamptz;

COMMENT ON COLUMN orders.platform_rts_at IS 'rts_time của sàn: đơn sẵn sàng bàn giao (AWAITING_COLLECTION)';
COMMENT ON COLUMN orders.platform_collected_at IS 'collection_time của sàn: đơn vị vận chuyển đã lấy hàng (IN_TRANSIT)';
COMMENT ON COLUMN orders.platform_delivered_at IS 'delivery_time của sàn: đã giao (DELIVERED); không phải thời điểm hoàn tất';
COMMENT ON COLUMN orders.platform_cancelled_at IS 'cancel_time của sàn: đơn bị hủy (CANCELLED)';
