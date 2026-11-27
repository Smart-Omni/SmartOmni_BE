-- =========================================================
-- SMARTOMNI - V30__loyalty_campaign_tiktok_batch_title.sql
-- A TikTok loyalty campaign hands out codes of one Seller Center code batch.
-- The batch is now linked explicitly by its exact title instead of being guessed
-- from the campaign (tier) name. NULL keeps the old behaviour (campaign name).
-- =========================================================

ALTER TABLE loyalty_reward_campaigns
    ADD COLUMN IF NOT EXISTS tiktok_batch_title VARCHAR(100);

COMMENT ON COLUMN loyalty_reward_campaigns.tiktok_batch_title IS
    'TikTok: exact title of the Seller Center code batch whose codes the campaign hands out';
