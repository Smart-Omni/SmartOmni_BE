-- Marketplace source state of a return request (simulator requirements phase 07b.2): the platform's own
-- return_status string and its update_time. An event older than the stored update_time is ignored.
ALTER TABLE returns_refunds ADD COLUMN marketplace_status varchar(64);
ALTER TABLE returns_refunds ADD COLUMN marketplace_updated_at timestamptz;

COMMENT ON COLUMN returns_refunds.marketplace_status IS 'return_status nguyên văn của sàn (ví dụ RETURN_OR_REFUND_REQUEST_SUCCESS)';
COMMENT ON COLUMN returns_refunds.marketplace_updated_at IS 'update_time của sàn cho marketplace_status; event cũ hơn bị bỏ qua';
