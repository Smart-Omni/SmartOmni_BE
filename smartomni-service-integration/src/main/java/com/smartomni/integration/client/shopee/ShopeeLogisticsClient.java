package com.smartomni.integration.client.shopee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartomni.integration.service.shopee.ShopeeShopApiExecutor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Client for Shopee Logistics, Fulfillment, and Airway Bill (AWB) APIs routed through {@link ShopeeShopApiExecutor}.
 */
@Component
public class ShopeeLogisticsClient {

    private final ShopeeShopApiExecutor executor;
    private final ObjectMapper mapper;

    public ShopeeLogisticsClient(ShopeeShopApiExecutor executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public record ChannelDetail(
            long channelId,
            String channelName,
            boolean codEnabled,
            boolean enabled,
            String feeType
    ) {}

    public record TimeSlot(String pickupTimeId, Long date, String timeText) {}

    public record PickupAddress(
            long addressId,
            String address,
            String district,
            String city,
            String state,
            String region,
            String zipcode,
            List<TimeSlot> timeSlots
    ) {}

    public record DropoffBranch(
            long branchId,
            String address,
            String district,
            String city,
            String state,
            String region,
            String zipcode
    ) {}

    public record ShippingParameter(
            List<String> dropoffInfoNeeded,
            List<String> pickupInfoNeeded,
            List<DropoffBranch> dropoffBranches,
            List<PickupAddress> pickupAddresses
    ) {}

    public record PickupParam(Long addressId, String pickupTimeId) {}
    public record DropoffParam(Long branchId) {}

    public record ShipOrderResult(boolean success, String orderSn) {}

    /**
     * Lists logistics channels supported by Shopee (GET /api/v2/logistics/get_channel_list).
     */
    public List<ChannelDetail> getChannelList(Long connectionId) {
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/logistics/get_channel_list", Collections.emptyMap(), null);
        List<ChannelDetail> channels = new ArrayList<>();
        JsonNode arr = data.path("logistics_channel_list");
        if (arr.isArray()) {
            for (JsonNode ch : arr) {
                channels.add(new ChannelDetail(
                        ch.path("logistics_channel_id").asLong(),
                        ch.path("logistics_channel_name").asText(""),
                        ch.path("cod_enabled").asBoolean(false),
                        ch.path("enabled").asBoolean(true),
                        ch.path("fee_type").asText("CUSTOM_PRICE")
                ));
            }
        }
        return channels;
    }

    /**
     * Retrieves shipping parameters (dropoff/pickup branches, addresses, time slots)
     * for an order (GET /api/v2/logistics/get_shipping_parameter).
     */
    public ShippingParameter getShippingParameter(Long connectionId, String orderSn) {
        Map<String, String> query = Map.of("order_sn", orderSn);
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/logistics/get_shipping_parameter", query, null);

        List<String> dropoffInfoNeeded = new ArrayList<>();
        JsonNode dInfo = data.path("info_needed").path("dropoff");
        if (dInfo.isArray()) {
            for (JsonNode it : dInfo) dropoffInfoNeeded.add(it.asText());
        }

        List<String> pickupInfoNeeded = new ArrayList<>();
        JsonNode pInfo = data.path("info_needed").path("pickup");
        if (pInfo.isArray()) {
            for (JsonNode it : pInfo) pickupInfoNeeded.add(it.asText());
        }

        List<DropoffBranch> branches = new ArrayList<>();
        JsonNode bArr = data.path("dropoff").path("branch_list");
        if (bArr.isArray()) {
            for (JsonNode b : bArr) {
                branches.add(new DropoffBranch(
                        b.path("branch_id").asLong(),
                        b.path("address").asText(""),
                        b.path("district").asText(""),
                        b.path("city").asText(""),
                        b.path("state").asText(""),
                        b.path("region").asText(""),
                        b.path("zipcode").asText("")
                ));
            }
        }

        List<PickupAddress> addresses = new ArrayList<>();
        JsonNode aArr = data.path("pickup").path("address_list");
        if (aArr.isArray()) {
            for (JsonNode a : aArr) {
                List<TimeSlot> slots = new ArrayList<>();
                JsonNode sArr = a.path("time_slot_list");
                if (sArr.isArray()) {
                    for (JsonNode s : sArr) {
                        slots.add(new TimeSlot(
                                s.path("pickup_time_id").asText(""),
                                s.hasNonNull("date") ? s.path("date").asLong() : null,
                                s.path("time_text").asText("")
                        ));
                    }
                }
                addresses.add(new PickupAddress(
                        a.path("address_id").asLong(),
                        a.path("address").asText(""),
                        a.path("district").asText(""),
                        a.path("city").asText(""),
                        a.path("state").asText(""),
                        a.path("region").asText(""),
                        a.path("zipcode").asText(""),
                        slots
                ));
            }
        }

        return new ShippingParameter(dropoffInfoNeeded, pickupInfoNeeded, branches, addresses);
    }

    /**
     * Initializes shipment for an order (POST /api/v2/logistics/ship_order).
     */
    public ShipOrderResult shipOrder(Long connectionId, String orderSn, String packageNumber,
                                     PickupParam pickup, DropoffParam dropoff) {
        ObjectNode body = mapper.createObjectNode();
        body.put("order_sn", orderSn);
        if (packageNumber != null && !packageNumber.isBlank()) {
            body.put("package_number", packageNumber);
        }

        if (pickup != null) {
            ObjectNode pNode = mapper.createObjectNode();
            if (pickup.addressId() != null) pNode.put("address_id", pickup.addressId());
            if (pickup.pickupTimeId() != null) pNode.put("pickup_time_id", pickup.pickupTimeId());
            body.set("pickup", pNode);
        } else if (dropoff != null) {
            ObjectNode dNode = mapper.createObjectNode();
            if (dropoff.branchId() != null) dNode.put("branch_id", dropoff.branchId());
            body.set("dropoff", dNode);
        }

        JsonNode res = executor.call(connectionId, "POST", "/api/v2/logistics/ship_order", Collections.emptyMap(), body.toString());
        return new ShipOrderResult(res != null, orderSn);
    }

    /**
     * Retrieves assigned logistics tracking number (GET /api/v2/logistics/get_tracking_number).
     */
    public String getTrackingNumber(Long connectionId, String orderSn, String packageNumber) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("order_sn", orderSn);
        if (packageNumber != null && !packageNumber.isBlank()) {
            query.put("package_number", packageNumber);
        }

        JsonNode data = executor.call(connectionId, "GET", "/api/v2/logistics/get_tracking_number", query, null);
        return data.path("tracking_number").asText("");
    }

    /**
     * Requests generation of shipping document (AWB PDF) (POST /api/v2/logistics/create_shipping_document).
     */
    public boolean createShippingDocument(Long connectionId, String orderSn, String packageNumber) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode orderArr = body.putArray("order_list");
        ObjectNode entry = mapper.createObjectNode();
        entry.put("order_sn", orderSn);
        if (packageNumber != null && !packageNumber.isBlank()) {
            entry.put("package_number", packageNumber);
        }
        orderArr.add(entry);

        JsonNode data = executor.call(connectionId, "POST", "/api/v2/logistics/create_shipping_document", Collections.emptyMap(), body.toString());
        return data != null;
    }

    /**
     * Checks shipping document generation status (GET /api/v2/logistics/get_shipping_document_result).
     */
    public String getShippingDocumentStatus(Long connectionId, String orderSn) {
        Map<String, String> query = Map.of("order_sn", orderSn);
        JsonNode data = executor.call(connectionId, "GET", "/api/v2/logistics/get_shipping_document_result", query, null);
        JsonNode resultArr = data.path("result_list");
        if (resultArr.isArray() && !resultArr.isEmpty()) {
            return resultArr.get(0).path("status").asText("NOT_CREATED");
        }
        return "NOT_CREATED";
    }

    /**
     * Downloads binary Airway Bill (AWB) PDF document (POST /api/v2/logistics/download_shipping_document).
     */
    public byte[] downloadShippingDocument(Long connectionId, String orderSn, String packageNumber) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode orderArr = body.putArray("order_list");
        ObjectNode entry = mapper.createObjectNode();
        entry.put("order_sn", orderSn);
        if (packageNumber != null && !packageNumber.isBlank()) {
            entry.put("package_number", packageNumber);
        }
        orderArr.add(entry);

        ShopeeHttpTransport.Response res = executor.callRaw(
                connectionId, "POST", "/api/v2/logistics/download_shipping_document",
                Collections.emptyMap(), body.toString()
        );

        if (res.status() >= 400) {
            throw new ShopeeApiException(res.status(), "DOWNLOAD_AWB_FAILED", res.body());
        }

        return res.body().getBytes(StandardCharsets.ISO_8859_1);
    }
}
