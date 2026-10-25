-- Read-only structural checks before explicitly baselining a reconciled database.
-- Run as the database administrator. Also compare row counts and pg_dump schema output.
BEGIN READ ONLY;
DO $$
DECLARE c record; actual_type text; actual_required boolean;
BEGIN
  FOR c IN SELECT * FROM (VALUES
    ('tenants','id','bigint',true),
    ('tenants','name','character varying',false),
    ('tenants','subdomain','character varying',true),
    ('tenants','status','tenant_status',false),
    ('tenants','plan_id','bigint',false),
    ('tenants','billing_cycle','billing_interval',false),
    ('tenants','next_billing_date','date',false),
    ('tenants','auto_renew','boolean',false),
    ('tenants','locked_reason','character varying',false),
    ('tenants','locked_by','bigint',false),
    ('tenants','locked_at','timestamp with time zone',false),
    ('tenants','created_at','timestamp with time zone',false),
    ('tenants','updated_at','timestamp with time zone',false),
    ('subscription_plans','id','bigint',true),
    ('subscription_plans','name','character varying',false),
    ('subscription_plans','price','numeric(14,2)',false),
    ('subscription_plans','billing_interval','billing_interval',false),
    ('subscription_plans','max_skus','integer',false),
    ('subscription_plans','max_marketplace_connections','integer',false),
    ('subscription_plans','storage_limit_mb','integer',false),
    ('subscription_plans','updated_by','bigint',false),
    ('subscription_plans','created_at','timestamp with time zone',false),
    ('subscription_plans','updated_at','timestamp with time zone',false),
    ('plan_feature_flags','updated_at','timestamp with time zone',false),
    ('plan_feature_flags','created_at','timestamp with time zone',false),
    ('plan_feature_flags','id','bigint',true),
    ('plan_feature_flags','plan_id','bigint',false),
    ('plan_feature_flags','feature_key','character varying',false),
    ('plan_feature_flags','enabled','boolean',false),
    ('tenant_subscription_history','updated_at','timestamp with time zone',false),
    ('tenant_subscription_history','created_at','timestamp with time zone',false),
    ('tenant_subscription_history','id','bigint',true),
    ('tenant_subscription_history','tenant_id','bigint',true),
    ('tenant_subscription_history','old_plan_id','bigint',false),
    ('tenant_subscription_history','new_plan_id','bigint',false),
    ('tenant_subscription_history','changed_by','bigint',false),
    ('tenant_subscription_history','changed_at','timestamp with time zone',false),
    ('resource_usage','updated_at','timestamp with time zone',false),
    ('resource_usage','created_at','timestamp with time zone',false),
    ('resource_usage','id','bigint',true),
    ('resource_usage','tenant_id','bigint',true),
    ('resource_usage','sku_count','integer',false),
    ('resource_usage','marketplace_connections_count','integer',false),
    ('resource_usage','storage_used_mb','integer',false),
    ('resource_usage','last_calculated_at','timestamp with time zone',false),
    ('tenant_invoices','updated_at','timestamp with time zone',false),
    ('tenant_invoices','id','bigint',true),
    ('tenant_invoices','tenant_id','bigint',true),
    ('tenant_invoices','plan_id','bigint',false),
    ('tenant_invoices','billing_period_start','date',false),
    ('tenant_invoices','billing_period_end','date',false),
    ('tenant_invoices','amount','numeric(14,2)',false),
    ('tenant_invoices','status','invoice_status',false),
    ('tenant_invoices','due_date','date',false),
    ('tenant_invoices','created_at','timestamp with time zone',false),
    ('subscription_payments','updated_at','timestamp with time zone',false),
    ('subscription_payments','id','bigint',true),
    ('subscription_payments','invoice_id','bigint',false),
    ('subscription_payments','tenant_id','bigint',true),
    ('subscription_payments','payment_method','character varying',false),
    ('subscription_payments','gateway_transaction_id','character varying',false),
    ('subscription_payments','amount','numeric(14,2)',false),
    ('subscription_payments','signature_valid','boolean',false),
    ('subscription_payments','is_anomaly','boolean',false),
    ('subscription_payments','confirmed_by','bigint',false),
    ('subscription_payments','status','payment_status',false),
    ('subscription_payments','paid_at','timestamp with time zone',false),
    ('subscription_payments','created_at','timestamp with time zone',false),
    ('users','id','bigint',true),
    ('users','tenant_id','bigint',false),
    ('users','email','character varying',true),
    ('users','password_hash','character varying',true),
    ('users','full_name','character varying',false),
    ('users','role','user_role',true),
    ('users','status','user_status',false),
    ('users','failed_login_attempts','integer',false),
    ('users','locked_until','timestamp with time zone',false),
    ('users','created_at','timestamp with time zone',false),
    ('users','updated_at','timestamp with time zone',false),
    ('password_reset_tokens','updated_at','timestamp with time zone',false),
    ('password_reset_tokens','id','bigint',true),
    ('password_reset_tokens','user_id','bigint',false),
    ('password_reset_tokens','token','character varying',true),
    ('password_reset_tokens','expires_at','timestamp with time zone',true),
    ('password_reset_tokens','used','boolean',false),
    ('password_reset_tokens','created_at','timestamp with time zone',false),
    ('auth_sessions','updated_at','timestamp with time zone',false),
    ('auth_sessions','created_at','timestamp with time zone',false),
    ('auth_sessions','id','bigint',true),
    ('auth_sessions','user_id','bigint',false),
    ('auth_sessions','jwt_id','character varying',true),
    ('auth_sessions','issued_at','timestamp with time zone',false),
    ('auth_sessions','expires_at','timestamp with time zone',false),
    ('auth_sessions','revoked','boolean',false),
    ('marketplace_connections','updated_at','timestamp with time zone',false),
    ('marketplace_connections','created_at','timestamp with time zone',false),
    ('marketplace_connections','id','bigint',true),
    ('marketplace_connections','tenant_id','bigint',true),
    ('marketplace_connections','platform','platform_type',true),
    ('marketplace_connections','shop_id','character varying',false),
    ('marketplace_connections','shop_name','character varying',false),
    ('marketplace_connections','app_key','character varying',false),
    ('marketplace_connections','app_secret_encrypted','character varying',false),
    ('marketplace_connections','webhook_url','character varying',false),
    ('marketplace_connections','status','connection_status',false),
    ('marketplace_connections','auto_import_products','boolean',false),
    ('marketplace_connections','connected_at','timestamp with time zone',false),
    ('products','id','bigint',true),
    ('products','tenant_id','bigint',true),
    ('products','name','character varying',true),
    ('products','description','text',false),
    ('products','status','product_status',false),
    ('products','created_by','bigint',false),
    ('products','created_at','timestamp with time zone',false),
    ('products','updated_at','timestamp with time zone',false),
    ('product_skus','updated_at','timestamp with time zone',false),
    ('product_skus','id','bigint',true),
    ('product_skus','product_id','bigint',false),
    ('product_skus','tenant_id','bigint',true),
    ('product_skus','sku_code','character varying',true),
    ('product_skus','variant_name','character varying',false),
    ('product_skus','base_price','numeric(14,2)',false),
    ('product_skus','created_at','timestamp with time zone',false),
    ('product_images','updated_at','timestamp with time zone',false),
    ('product_images','id','bigint',true),
    ('product_images','product_id','bigint',false),
    ('product_images','sku_id','bigint',false),
    ('product_images','tenant_id','bigint',true),
    ('product_images','url','character varying',true),
    ('product_images','sort_order','integer',false),
    ('product_images','created_at','timestamp with time zone',false),
    ('product_marketplace_links','updated_at','timestamp with time zone',false),
    ('product_marketplace_links','id','bigint',true),
    ('product_marketplace_links','sku_id','bigint',false),
    ('product_marketplace_links','connection_id','bigint',false),
    ('product_marketplace_links','tenant_id','bigint',true),
    ('product_marketplace_links','platform','platform_type',true),
    ('product_marketplace_links','platform_item_id','character varying',false),
    ('product_marketplace_links','platform_product_url','character varying',false),
    ('product_marketplace_links','platform_price','numeric(14,2)',false),
    ('product_marketplace_links','last_synced_at','timestamp with time zone',false),
    ('product_marketplace_links','created_at','timestamp with time zone',false),
    ('price_sync_configs','updated_at','timestamp with time zone',false),
    ('price_sync_configs','id','bigint',true),
    ('price_sync_configs','tenant_id','bigint',true),
    ('price_sync_configs','scope','sync_scope',true),
    ('price_sync_configs','sku_id','bigint',false),
    ('price_sync_configs','platform','platform_type',false),
    ('price_sync_configs','sync_enabled','boolean',false),
    ('price_sync_configs','sync_frequency_minutes','integer',false),
    ('price_sync_configs','created_at','timestamp with time zone',false),
    ('price_sync_history','updated_at','timestamp with time zone',false),
    ('price_sync_history','created_at','timestamp with time zone',false),
    ('price_sync_history','id','bigint',true),
    ('price_sync_history','sync_config_id','bigint',false),
    ('price_sync_history','sku_id','bigint',false),
    ('price_sync_history','tenant_id','bigint',true),
    ('price_sync_history','platform','platform_type',false),
    ('price_sync_history','old_price','numeric(14,2)',false),
    ('price_sync_history','new_price','numeric(14,2)',false),
    ('price_sync_history','is_anomaly','boolean',false),
    ('price_sync_history','confirmed_by','bigint',false),
    ('price_sync_history','changed_at','timestamp with time zone',false),
    ('inventory','created_at','timestamp with time zone',false),
    ('inventory','id','bigint',true),
    ('inventory','sku_id','bigint',false),
    ('inventory','tenant_id','bigint',true),
    ('inventory','quantity_on_hand','integer',false),
    ('inventory','reserved_quantity','integer',false),
    ('inventory','version','integer',false),
    ('inventory','updated_at','timestamp with time zone',false),
    ('inventory_outbox_events','updated_at','timestamp with time zone',false),
    ('inventory_outbox_events','id','bigint',true),
    ('inventory_outbox_events','sku_id','bigint',false),
    ('inventory_outbox_events','tenant_id','bigint',true),
    ('inventory_outbox_events','platform','platform_type',false),
    ('inventory_outbox_events','quantity','integer',false),
    ('inventory_outbox_events','status','outbox_status',false),
    ('inventory_outbox_events','retry_count','integer',false),
    ('inventory_outbox_events','created_at','timestamp with time zone',false),
    ('inventory_outbox_events','processed_at','timestamp with time zone',false),
    ('orders','id','bigint',true),
    ('orders','tenant_id','bigint',true),
    ('orders','connection_id','bigint',false),
    ('orders','platform','platform_type',true),
    ('orders','platform_order_id','character varying',false),
    ('orders','status','order_status',false),
    ('orders','sla_deadline','timestamp with time zone',false),
    ('orders','shipping_method','character varying',false),
    ('orders','tracking_number','character varying',false),
    ('orders','courier_scanned_at','timestamp with time zone',false),
    ('orders','customer_name','character varying',false),
    ('orders','customer_phone','character varying',false),
    ('orders','shipping_address','text',false),
    ('orders','total_amount','numeric(14,2)',false),
    ('orders','source','order_source',false),
    ('orders','created_at','timestamp with time zone',false),
    ('orders','updated_at','timestamp with time zone',false),
    ('order_items','updated_at','timestamp with time zone',false),
    ('order_items','created_at','timestamp with time zone',false),
    ('order_items','id','bigint',true),
    ('order_items','order_id','bigint',false),
    ('order_items','tenant_id','bigint',true),
    ('order_items','sku_id','bigint',false),
    ('order_items','quantity','integer',false),
    ('order_items','unit_price','numeric(14,2)',false),
    ('order_status_history','updated_at','timestamp with time zone',false),
    ('order_status_history','created_at','timestamp with time zone',false),
    ('order_status_history','id','bigint',true),
    ('order_status_history','order_id','bigint',false),
    ('order_status_history','tenant_id','bigint',true),
    ('order_status_history','status','order_status',false),
    ('order_status_history','source','order_change_source',true),
    ('order_status_history','changed_by','bigint',false),
    ('order_status_history','changed_at','timestamp with time zone',false),
    ('returns_refunds','updated_at','timestamp with time zone',false),
    ('returns_refunds','id','bigint',true),
    ('returns_refunds','order_id','bigint',false),
    ('returns_refunds','tenant_id','bigint',true),
    ('returns_refunds','trigger_type','return_trigger_type',true),
    ('returns_refunds','return_tracking_number','character varying',false),
    ('returns_refunds','reason','text',false),
    ('returns_refunds','status','refund_status',false),
    ('returns_refunds','reviewed_by','bigint',false),
    ('returns_refunds','received_at','timestamp with time zone',false),
    ('returns_refunds','video_evidence_url','character varying',false),
    ('returns_refunds','inspection_result','inspection_result_type',false),
    ('returns_refunds','restocked','boolean',false),
    ('returns_refunds','dispute_status','dispute_status_type',false),
    ('returns_refunds','compensation_amount','numeric(14,2)',false),
    ('returns_refunds','created_at','timestamp with time zone',false),
    ('webhook_events_log','updated_at','timestamp with time zone',false),
    ('webhook_events_log','created_at','timestamp with time zone',false),
    ('webhook_events_log','id','bigint',true),
    ('webhook_events_log','tenant_id','bigint',true),
    ('webhook_events_log','platform','platform_type',false),
    ('webhook_events_log','event_id','character varying',false),
    ('webhook_events_log','event_type','character varying',false),
    ('webhook_events_log','order_id','bigint',false),
    ('webhook_events_log','signature_valid','boolean',false),
    ('webhook_events_log','raw_payload','text',false),
    ('webhook_events_log','queued','boolean',false),
    ('webhook_events_log','processed_at','timestamp with time zone',false),
    ('webhook_events_log','received_at','timestamp with time zone',false),
    ('seller_wallet_ledger','updated_at','timestamp with time zone',false),
    ('seller_wallet_ledger','created_at','timestamp with time zone',false),
    ('seller_wallet_ledger','id','bigint',true),
    ('seller_wallet_ledger','tenant_id','bigint',true),
    ('seller_wallet_ledger','order_id','bigint',false),
    ('seller_wallet_ledger','gross_amount','numeric(14,2)',false),
    ('seller_wallet_ledger','platform_fee','numeric(14,2)',false),
    ('seller_wallet_ledger','freeship_fee','numeric(14,2)',false),
    ('seller_wallet_ledger','net_payout','numeric(14,2)',false),
    ('seller_wallet_ledger','recorded_at','timestamp with time zone',false),
    ('promotion_events','updated_at','timestamp with time zone',false),
    ('promotion_events','id','bigint',true),
    ('promotion_events','tenant_id','bigint',true),
    ('promotion_events','name','character varying',false),
    ('promotion_events','discount_percent','numeric(5,2)',false),
    ('promotion_events','start_date','date',false),
    ('promotion_events','end_date','date',false),
    ('promotion_events','applies_to','promotion_scope',false),
    ('promotion_events','created_by','bigint',false),
    ('promotion_events','created_at','timestamp with time zone',false),
    ('promotion_items','updated_at','timestamp with time zone',false),
    ('promotion_items','created_at','timestamp with time zone',false),
    ('promotion_items','id','bigint',true),
    ('promotion_items','promotion_id','bigint',false),
    ('promotion_items','sku_id','bigint',false),
    ('ai_model_versions','updated_at','timestamp with time zone',false),
    ('ai_model_versions','created_at','timestamp with time zone',false),
    ('ai_model_versions','id','bigint',true),
    ('ai_model_versions','tenant_id','bigint',true),
    ('ai_model_versions','model_type','model_type',true),
    ('ai_model_versions','version_number','character varying',false),
    ('ai_model_versions','rmse','numeric(10,4)',false),
    ('ai_model_versions','status','model_status',false),
    ('ai_model_versions','trained_at','timestamp with time zone',false),
    ('demand_forecasts','updated_at','timestamp with time zone',false),
    ('demand_forecasts','id','bigint',true),
    ('demand_forecasts','tenant_id','bigint',true),
    ('demand_forecasts','sku_id','bigint',false),
    ('demand_forecasts','model_version_id','bigint',false),
    ('demand_forecasts','forecast_date','date',false),
    ('demand_forecasts','predicted_quantity','integer',false),
    ('demand_forecasts','recommended_reorder_qty','integer',false),
    ('demand_forecasts','created_at','timestamp with time zone',false),
    ('sales_outlier_exclusions','updated_at','timestamp with time zone',false),
    ('sales_outlier_exclusions','created_at','timestamp with time zone',false),
    ('sales_outlier_exclusions','id','bigint',true),
    ('sales_outlier_exclusions','tenant_id','bigint',true),
    ('sales_outlier_exclusions','sku_id','bigint',false),
    ('sales_outlier_exclusions','order_id','bigint',false),
    ('sales_outlier_exclusions','reason','character varying',false),
    ('sales_outlier_exclusions','excluded_at','timestamp with time zone',false),
    ('user_item_interactions','updated_at','timestamp with time zone',false),
    ('user_item_interactions','id','bigint',true),
    ('user_item_interactions','tenant_id','bigint',true),
    ('user_item_interactions','customer_identifier','character varying',false),
    ('user_item_interactions','sku_id','bigint',false),
    ('user_item_interactions','interaction_type','interaction_type',true),
    ('user_item_interactions','weight','integer',false),
    ('user_item_interactions','created_at','timestamp with time zone',false),
    ('product_recommendations_cache','updated_at','timestamp with time zone',false),
    ('product_recommendations_cache','created_at','timestamp with time zone',false),
    ('product_recommendations_cache','id','bigint',true),
    ('product_recommendations_cache','tenant_id','bigint',true),
    ('product_recommendations_cache','source_sku_id','bigint',false),
    ('product_recommendations_cache','recommended_sku_id','bigint',false),
    ('product_recommendations_cache','similarity_score','numeric(6,4)',false),
    ('product_recommendations_cache','computed_at','timestamp with time zone',false),
    ('redirect_click_events','updated_at','timestamp with time zone',false),
    ('redirect_click_events','created_at','timestamp with time zone',false),
    ('redirect_click_events','id','bigint',true),
    ('redirect_click_events','tenant_id','bigint',true),
    ('redirect_click_events','sku_id','bigint',false),
    ('redirect_click_events','platform','platform_type',false),
    ('redirect_click_events','clicked_at','timestamp with time zone',false),
    ('support_tickets','updated_at','timestamp with time zone',false),
    ('support_tickets','id','bigint',true),
    ('support_tickets','tenant_id','bigint',true),
    ('support_tickets','created_by','bigint',false),
    ('support_tickets','assigned_to','bigint',false),
    ('support_tickets','subject','character varying',false),
    ('support_tickets','description','text',false),
    ('support_tickets','status','ticket_status',false),
    ('support_tickets','created_at','timestamp with time zone',false),
    ('support_tickets','resolved_at','timestamp with time zone',false),
    ('security_audit_logs','updated_at','timestamp with time zone',false),
    ('security_audit_logs','created_at','timestamp with time zone',false),
    ('security_audit_logs','id','bigint',true),
    ('security_audit_logs','tenant_id','bigint',false),
    ('security_audit_logs','event_type','security_event_type',true),
    ('security_audit_logs','details','text',false),
    ('security_audit_logs','session_tenant_id','bigint',false),
    ('security_audit_logs','detected_at','timestamp with time zone',false),
    ('system_metric_alerts','updated_at','timestamp with time zone',false),
    ('system_metric_alerts','created_at','timestamp with time zone',false),
    ('system_metric_alerts','id','bigint',true),
    ('system_metric_alerts','metric_name','character varying',false),
    ('system_metric_alerts','threshold_value','numeric(14,4)',false),
    ('system_metric_alerts','current_value','numeric(14,4)',false),
    ('system_metric_alerts','triggered_at','timestamp with time zone',false),
    ('system_metric_alerts','resolved','boolean',false)
  ) AS expected(table_name, column_name, type_name, required) LOOP
    SELECT format_type(a.atttypid, a.atttypmod), a.attnotnull INTO actual_type, actual_required
    FROM pg_attribute a JOIN pg_class t ON t.oid=a.attrelid
    WHERE t.relnamespace='public'::regnamespace AND t.relname=c.table_name
          AND a.attname=c.column_name AND a.attnum > 0 AND NOT a.attisdropped;
    IF NOT FOUND OR actual_type IS DISTINCT FROM c.type_name
       OR (c.required AND NOT actual_required) THEN
      RAISE EXCEPTION 'Schema mismatch %.%: expected % (required=%), actual % (required=%)',
        c.table_name, c.column_name, c.type_name, c.required, actual_type, actual_required;
    END IF;
  END LOOP;
  FOR c IN SELECT unnest(ARRAY['tenants','subscription_plans','plan_feature_flags','tenant_subscription_history','resource_usage','tenant_invoices','subscription_payments','users','password_reset_tokens','auth_sessions','marketplace_connections','products','product_skus','product_images','product_marketplace_links','price_sync_configs','price_sync_history','inventory','inventory_outbox_events','orders','order_items','order_status_history','returns_refunds','webhook_events_log','seller_wallet_ledger','promotion_events','promotion_items','ai_model_versions','demand_forecasts','sales_outlier_exclusions','user_item_interactions','product_recommendations_cache','redirect_click_events','support_tickets','security_audit_logs','system_metric_alerts']) AS name LOOP
    IF NOT EXISTS (SELECT FROM pg_class WHERE relnamespace='public'::regnamespace AND relname=c.name
                   AND relkind='r' AND relrowsecurity AND relforcerowsecurity
                   AND relowner='smartomni_migrator'::regrole) THEN
      RAISE EXCEPTION 'Expected tenant policies and migration ownership for table %', c.name;
    END IF;
  END LOOP;
  IF EXISTS (SELECT FROM pg_roles WHERE rolname='smartomni_app' AND (rolsuper OR rolbypassrls)) THEN
    RAISE EXCEPTION 'Runtime role must not be superuser or bypass RLS';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_proc WHERE pronamespace='public'::regnamespace
                 AND proname='app_current_tenant_id' AND prorettype='bigint'::regtype) THEN
    RAISE EXCEPTION 'Tenant context helper must return bigint';
  END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='user_role') IS DISTINCT FROM ARRAY['super_admin','admin','manager','tenant_admin','customer']
     AND (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='user_role') IS DISTINCT FROM ARRAY['super_admin','admin','manager'] THEN RAISE EXCEPTION 'Enum mismatch: user_role'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='user_status') IS DISTINCT FROM ARRAY['active','disabled'] THEN RAISE EXCEPTION 'Enum mismatch: user_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='tenant_status') IS DISTINCT FROM ARRAY['active','locked'] THEN RAISE EXCEPTION 'Enum mismatch: tenant_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='platform_type') IS DISTINCT FROM ARRAY['shopee','tiktok_shop','storefront'] THEN RAISE EXCEPTION 'Enum mismatch: platform_type'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='connection_status') NOT IN (ARRAY['connected','error','disconnected'], ARRAY['connected','error','disconnected','missing_scope','refresh_failed','revoked']) THEN RAISE EXCEPTION 'Enum mismatch: connection_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='product_status') IS DISTINCT FROM ARRAY['active','hidden'] THEN RAISE EXCEPTION 'Enum mismatch: product_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='order_status') IS DISTINCT FROM ARRAY['pending','to_ship','shipped','delivering','completed','cancelled','returned'] THEN RAISE EXCEPTION 'Enum mismatch: order_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='order_change_source') IS DISTINCT FROM ARRAY['manager','marketplace_webhook','system'] THEN RAISE EXCEPTION 'Enum mismatch: order_change_source'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='order_source') IS DISTINCT FROM ARRAY['webhook','polling','manual'] THEN RAISE EXCEPTION 'Enum mismatch: order_source'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='return_trigger_type') IS DISTINCT FROM ARRAY['refused_delivery','customer_request'] THEN RAISE EXCEPTION 'Enum mismatch: return_trigger_type'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='refund_status') IS DISTINCT FROM ARRAY['requested','approved','rejected','completed'] THEN RAISE EXCEPTION 'Enum mismatch: refund_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='inspection_result_type') IS DISTINCT FROM ARRAY['pending','matched','damaged','swapped','missing_items'] THEN RAISE EXCEPTION 'Enum mismatch: inspection_result_type'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='dispute_status_type') IS DISTINCT FROM ARRAY['none','filed','approved','denied'] THEN RAISE EXCEPTION 'Enum mismatch: dispute_status_type'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='outbox_status') IS DISTINCT FROM ARRAY['pending','sent','failed'] THEN RAISE EXCEPTION 'Enum mismatch: outbox_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='promotion_scope') IS DISTINCT FROM ARRAY['all','category','sku'] THEN RAISE EXCEPTION 'Enum mismatch: promotion_scope'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='model_type') IS DISTINCT FROM ARRAY['demand_forecasting','recommendation'] THEN RAISE EXCEPTION 'Enum mismatch: model_type'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='model_status') IS DISTINCT FROM ARRAY['training','active','rolled_back'] THEN RAISE EXCEPTION 'Enum mismatch: model_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='interaction_type') IS DISTINCT FROM ARRAY['view','cart','purchase'] THEN RAISE EXCEPTION 'Enum mismatch: interaction_type'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='ticket_status') IS DISTINCT FROM ARRAY['open','in_progress','resolved'] THEN RAISE EXCEPTION 'Enum mismatch: ticket_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='security_event_type') IS DISTINCT FROM ARRAY['rls_violation','cross_tenant_test','login_failure','brute_force_lock'] THEN RAISE EXCEPTION 'Enum mismatch: security_event_type'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='sync_scope') IS DISTINCT FROM ARRAY['tenant','product'] THEN RAISE EXCEPTION 'Enum mismatch: sync_scope'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='billing_interval') IS DISTINCT FROM ARRAY['monthly','yearly'] THEN RAISE EXCEPTION 'Enum mismatch: billing_interval'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='invoice_status') IS DISTINCT FROM ARRAY['pending','paid','overdue','cancelled'] THEN RAISE EXCEPTION 'Enum mismatch: invoice_status'; END IF;
  IF (SELECT array_agg(e.enumlabel::text ORDER BY e.enumsortorder) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typnamespace='public'::regnamespace AND t.typname='payment_status') IS DISTINCT FROM ARRAY['pending','success','failed'] THEN RAISE EXCEPTION 'Enum mismatch: payment_status'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_tenants_plan') THEN RAISE EXCEPTION 'Missing constraint: fk_tenants_plan'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_tenants_locked_by') THEN RAISE EXCEPTION 'Missing constraint: fk_tenants_locked_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_plan_feature_flags_plan') THEN RAISE EXCEPTION 'Missing constraint: fk_plan_feature_flags_plan'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_tsh_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_tsh_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_tsh_old_plan') THEN RAISE EXCEPTION 'Missing constraint: fk_tsh_old_plan'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_tsh_new_plan') THEN RAISE EXCEPTION 'Missing constraint: fk_tsh_new_plan'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_tsh_changed_by') THEN RAISE EXCEPTION 'Missing constraint: fk_tsh_changed_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_resource_usage_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_resource_usage_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_invoices_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_invoices_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_invoices_plan') THEN RAISE EXCEPTION 'Missing constraint: fk_invoices_plan'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_sub_payments_invoice') THEN RAISE EXCEPTION 'Missing constraint: fk_sub_payments_invoice'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_sub_payments_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_sub_payments_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_sub_payments_confirmed_by') THEN RAISE EXCEPTION 'Missing constraint: fk_sub_payments_confirmed_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_users_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_users_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_prt_user') THEN RAISE EXCEPTION 'Missing constraint: fk_prt_user'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_auth_sessions_user') THEN RAISE EXCEPTION 'Missing constraint: fk_auth_sessions_user'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_mc_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_mc_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_products_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_products_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_products_created_by') THEN RAISE EXCEPTION 'Missing constraint: fk_products_created_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_skus_product') THEN RAISE EXCEPTION 'Missing constraint: fk_skus_product'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_skus_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_skus_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pimg_product') THEN RAISE EXCEPTION 'Missing constraint: fk_pimg_product'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pimg_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_pimg_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pimg_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_pimg_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pml_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_pml_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pml_connection') THEN RAISE EXCEPTION 'Missing constraint: fk_pml_connection'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pml_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_pml_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_psc_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_psc_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_psc_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_psc_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_psh_config') THEN RAISE EXCEPTION 'Missing constraint: fk_psh_config'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_psh_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_psh_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_psh_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_psh_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_psh_confirmed_by') THEN RAISE EXCEPTION 'Missing constraint: fk_psh_confirmed_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_inventory_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_inventory_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_inventory_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_inventory_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_ioe_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_ioe_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_ioe_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_ioe_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_orders_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_orders_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_orders_connection') THEN RAISE EXCEPTION 'Missing constraint: fk_orders_connection'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_oi_order') THEN RAISE EXCEPTION 'Missing constraint: fk_oi_order'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_oi_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_oi_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_oi_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_oi_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_osh_order') THEN RAISE EXCEPTION 'Missing constraint: fk_osh_order'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_osh_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_osh_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_osh_changed_by') THEN RAISE EXCEPTION 'Missing constraint: fk_osh_changed_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_rr_order') THEN RAISE EXCEPTION 'Missing constraint: fk_rr_order'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_rr_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_rr_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_rr_reviewed_by') THEN RAISE EXCEPTION 'Missing constraint: fk_rr_reviewed_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_wel_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_wel_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_wel_order') THEN RAISE EXCEPTION 'Missing constraint: fk_wel_order'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_swl_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_swl_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_swl_order') THEN RAISE EXCEPTION 'Missing constraint: fk_swl_order'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pe_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_pe_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pe_created_by') THEN RAISE EXCEPTION 'Missing constraint: fk_pe_created_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pi_promotion') THEN RAISE EXCEPTION 'Missing constraint: fk_pi_promotion'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_pi_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_pi_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_amv_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_amv_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_df_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_df_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_df_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_df_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_df_model_version') THEN RAISE EXCEPTION 'Missing constraint: fk_df_model_version'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_soe_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_soe_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_soe_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_soe_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_soe_order') THEN RAISE EXCEPTION 'Missing constraint: fk_soe_order'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_uii_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_uii_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_uii_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_uii_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_prc_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_prc_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_prc_source_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_prc_source_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_prc_rec_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_prc_rec_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_rce_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_rce_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_rce_sku') THEN RAISE EXCEPTION 'Missing constraint: fk_rce_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_st_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_st_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_st_created_by') THEN RAISE EXCEPTION 'Missing constraint: fk_st_created_by'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_st_assigned_to') THEN RAISE EXCEPTION 'Missing constraint: fk_st_assigned_to'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='fk_sal_tenant') THEN RAISE EXCEPTION 'Missing constraint: fk_sal_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_tenants_subdomain') THEN RAISE EXCEPTION 'Missing constraint: uq_tenants_subdomain'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_subscription_plans_name') THEN RAISE EXCEPTION 'Missing constraint: uq_subscription_plans_name'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_plan_feature_flags') THEN RAISE EXCEPTION 'Missing constraint: uq_plan_feature_flags'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_resource_usage_tenant') THEN RAISE EXCEPTION 'Missing constraint: uq_resource_usage_tenant'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_subscription_payments_gw_txn') THEN RAISE EXCEPTION 'Missing constraint: uq_subscription_payments_gw_txn'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_users_email') THEN RAISE EXCEPTION 'Missing constraint: uq_users_email'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_password_reset_tokens_token') THEN RAISE EXCEPTION 'Missing constraint: uq_password_reset_tokens_token'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_auth_sessions_jwt_id') THEN RAISE EXCEPTION 'Missing constraint: uq_auth_sessions_jwt_id'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_marketplace_connections') THEN RAISE EXCEPTION 'Missing constraint: uq_marketplace_connections'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_product_skus_code') THEN RAISE EXCEPTION 'Missing constraint: uq_product_skus_code'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_product_marketplace_links') THEN RAISE EXCEPTION 'Missing constraint: uq_product_marketplace_links'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_inventory_sku') THEN RAISE EXCEPTION 'Missing constraint: uq_inventory_sku'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_orders_platform_order') THEN RAISE EXCEPTION 'Missing constraint: uq_orders_platform_order'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_webhook_events_log') THEN RAISE EXCEPTION 'Missing constraint: uq_webhook_events_log'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_seller_wallet_ledger_order') THEN RAISE EXCEPTION 'Missing constraint: uq_seller_wallet_ledger_order'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_promotion_items') THEN RAISE EXCEPTION 'Missing constraint: uq_promotion_items'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_demand_forecasts') THEN RAISE EXCEPTION 'Missing constraint: uq_demand_forecasts'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_sales_outlier_exclusions') THEN RAISE EXCEPTION 'Missing constraint: uq_sales_outlier_exclusions'; END IF;
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname='uq_product_recommendations_cache') THEN RAISE EXCEPTION 'Missing constraint: uq_product_recommendations_cache'; END IF;
END $$;
COMMIT;
