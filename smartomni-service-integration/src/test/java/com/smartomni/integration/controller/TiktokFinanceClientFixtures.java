package com.smartomni.integration.controller;

/** Payload shaped like the simulator's GET /order/202407/orders/{id}/price_detail (orders.controller.ts). */
final class TiktokFinanceClientFixtures {

    static final String PRICE_DETAIL = """
            {"currency":"VND","sku_list_price":"260000","sku_sale_price":"240000","sku_gift_original_price":"0",
             "sku_gift_net_price":"0","subtotal_deduction_seller":"20000","subtotal_deduction_platform":"0",
             "subtotal_tax_amount":"0","subtotal":"240000","voucher_deduction_seller":"0","voucher_deduction_platform":"0",
             "shipping_list_price":"30000","shipping_fee_deduction_seller":"5000","shipping_fee_deduction_platform":"0",
             "shipping_fee_deduction_platform_voucher":"0","shipping_sale_price":"25000","tax_amount":"0","tax_rate":"0",
             "net_price_amount":"240000","cod_fee":"0","distance_fee":"0","distance_shipping_fee":"0",
             "payment":"265000","total":"290000","cod_fee_net_amount":"0",
             "line_items":[
               {"id":"OL-1","currency":"VND","sku_list_price":"160000","sku_sale_price":"148000","subtotal_deduction_seller":"12000",
                "subtotal_deduction_platform":"0","subtotal":"148000","shipping_list_price":"15000","shipping_fee_deduction_seller":"3000",
                "shipping_fee_deduction_platform":"0","shipping_sale_price":"12000","tax_amount":"0","payment":"160000","total":"175000"},
               {"id":"OL-2","currency":"VND","sku_list_price":"100000","sku_sale_price":"92000","subtotal_deduction_seller":"8000",
                "subtotal_deduction_platform":"0","subtotal":"92000","shipping_list_price":"15000","shipping_fee_deduction_seller":"2000",
                "shipping_fee_deduction_platform":"0","shipping_sale_price":"13000","tax_amount":"0","payment":"105000","total":"115000"}]}
            """;

    private TiktokFinanceClientFixtures() {
    }
}
