# SmartOmni Detailed Design

# **Appendix Diagram Source Code**

The following listings are the editable PlantUML class diagram source and Mermaid sequenceDiagram source.

## **3.1 User Authentication**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.1 User Authentication - Login
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
skinparam class {
  BackgroundColor #F7FAFC
  BorderColor #334155
  ArrowColor #475569
}
left to right direction

class AuthController <<REST controller>> {
  +login(request: LoginRequest): ApiResponse<AuthResponse>
  +refreshToken(request: RefreshTokenRequest): ApiResponse<AuthResponse>
}

interface AuthService {
  +login(request: LoginRequest): AuthResponse
  +refreshToken(request: RefreshTokenRequest): AuthResponse
}

class AuthServiceImpl <<service>> {
  +login(request: LoginRequest): AuthResponse
  +refreshToken(request: RefreshTokenRequest): AuthResponse
  -storeRefreshTokenInRedis(userId: Long): String
}

interface UserRepository <<repository>> {
  +findByEmail(email: String): Optional<User>
  +save(user: User): User
}
class TenantLookupRepository <<repository>> {
  +findBySubdomain(subdomain: String): Optional<TenantLookup>
  +findById(id: Long): Optional<TenantLookup>
}
interface UserLoginAttemptService {
  +recordFailedAttempt(userId: Long): void
  +resetFailedAttempts(userId: Long): void
}
class JwtTokenProvider <<security>> {
  +generateToken(userId: Long, role: String, tenantId: Long, subdomain: String): String
}
class StringRedisTemplate <<Redis client>>

class LoginRequest <<DTO>> {
  +email: String
  +password: String
  +subdomain: String
  +tenantId: Long
}
class AuthResponse <<DTO>> {
  +accessToken: String
  +refreshToken: String
  +tokenType: String
  +expiresIn: Long
}

AuthController --> AuthService
AuthService <|.. AuthServiceImpl
AuthController ..> LoginRequest
AuthServiceImpl ..> AuthResponse
AuthServiceImpl --> UserRepository
AuthServiceImpl --> TenantLookupRepository
AuthServiceImpl --> UserLoginAttemptService
AuthServiceImpl --> JwtTokenProvider
AuthServiceImpl --> StringRedisTemplate
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor User
participant Controller as AuthController
participant Service as AuthServiceImpl
participant Users as UserRepository
participant Encoder as PasswordEncoder
participant Tenants as TenantLookupRepository
participant Attempts as UserLoginAttemptService
participant JWT as JwtTokenProvider
participant Redis as StringRedisTemplate
User->>Controller: login(LoginRequest)
Controller->>Service: login(request)
Service->>Users: findByEmail(email)
alt User missing or locked
Service-->>Controller: Authentication error
else Active user
Service->>Encoder: matches(password, passwordHash)
alt Incorrect password
Service->>Attempts: recordFailedAttempt(userId)
else Correct password
opt Tenant specified
Service->>Tenants: findBySubdomain or findById
end
Service->>Attempts: resetFailedAttempts(userId)
Service->>JWT: generateToken(userId, role, tenantId, subdomain)
Service->>Redis: Store expiring refresh token
Service-->>Controller: AuthResponse
end
end

```

## **3.2 Tenant Onboarding and Management**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.2 Tenant Onboarding and Management
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class TenantController {
  +onboard(TenantOnboardingRequest)
  +lock(Long, LockTenantRequest)
  +unlock(Long)
}
class TenantService {
  +onboardTenant(TenantOnboardingRequest)
  +lockTenant(Long, String, Long)
  +unlockTenant(Long)
}
class TenantServiceImpl {
  +onboardTenant(TenantOnboardingRequest)
  +checkSubdomain(String)
}
class TenantRepository {
  +save(Tenant)
  +findById(Long)
}
class SubscriptionPlanRepository {
  +findByName(String)
  +save(SubscriptionPlan)
}
class UserRepository {
  +findByEmail(String)
  +save(TenantUser)
}
TenantController --> TenantService
TenantService <|.. TenantServiceImpl
TenantServiceImpl --> TenantRepository
TenantServiceImpl --> SubscriptionPlanRepository
TenantServiceImpl --> UserRepository
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Admin
participant C as TenantController
participant S as TenantServiceImpl
participant T as TenantRepository
participant P as SubscriptionPlanRepository
participant U as UserRepository
Admin->>C: onboard(TenantOnboardingRequest)
C->>S: onboardTenant(request)
S->>S: checkSubdomain(request.subdomain)
S->>U: findByEmail(adminEmail)
alt Subdomain or email already exists
S-->>C: BusinessException
else Valid request
S->>P: findByName(FREE or PRO)
opt Plan absent
S->>P: save(SubscriptionPlan)
end
S->>T: save(Tenant)
S->>U: save(TenantUser with TENANT\_ADMIN role)
S-->>C: TenantProfileResponse
C-->>Admin: ApiResponse
end

```

## **3.4 Marketplace Connection Management**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.4 Marketplace Connection Management - Shopee OAuth
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class ShopeeConnectionController {
  +start()
  +callback(ShopeeCallbackRequest)
  +current()
  +disconnect()
}
class ShopeeConnectionService {
  +start()
  +complete(ShopeeCallbackRequest)
  +current()
  +disconnect()
}
class ShopeeConnectionStore {
  +prepareStart(...)
  +consumeState(...)
  +link(...)
}
class ShopeeAuthClient {
  +exchangeAuthorizationCode(...)
}
class ShopeeOpenApiClient {
  +getShopInfo(...)
}
class TokenCipher {
  +encrypt(String)
}
ShopeeConnectionController --> ShopeeConnectionService
ShopeeConnectionService --> ShopeeConnectionStore
ShopeeConnectionService --> ShopeeAuthClient
ShopeeConnectionService --> ShopeeOpenApiClient
ShopeeConnectionService --> TokenCipher
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Admin
participant C as ShopeeConnectionController
participant S as ShopeeConnectionService
participant Store as ShopeeConnectionStore
participant Auth as ShopeeAuthClient
participant API as ShopeeOpenApiClient
participant Cipher as TokenCipher
Admin->>C: start()
C->>S: start()
S->>Store: prepareStart(tenantId, userId, stateHash, expiry)
S-->>Admin: authorizationUrl
Admin->>C: callback(ShopeeCallbackRequest)
C->>S: complete(request)
S->>Store: consumeState(...)
alt Invalid or expired state
S-->>C: BusinessException
else Valid state and code
S->>Auth: exchangeAuthorizationCode(...)
S->>API: getShopInfo(...)
S->>Cipher: encrypt(accessToken, refreshToken)
S->>Store: link(tenantId, connectionId, LinkRequest)
S-->>C: ShopeeConnectionDto
end

```

## **3.5 Product and SKU Management**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.5 Product and SKU Management
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class ProductController {
  +create(CreateProductRequest)
  +getById(Long)
  +getSkus(Long)
}
class ProductService {
  +createProduct(CreateProductRequest)
  +getProductOrThrow(Long)
  +getSkusByProduct(Long)
}
class ProductRepository {
  +save(Product)
  +findById(Long)
}
class ProductSkuRepository {
  +existsByTenantIdAndSkuCode(Long, String)
  +save(ProductSku)
}
class Product {
  +tenantId
  +name
  +description
}
class ProductSku {
  +tenantId
  +productId
  +skuCode
  +basePrice
}
ProductController --> ProductService
ProductService --> ProductRepository
ProductService --> ProductSkuRepository
Product --> ProductSku
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Manager
participant C as ProductController
participant S as ProductService
participant SR as ProductSkuRepository
participant PR as ProductRepository
Manager->>C: create(CreateProductRequest)
C->>S: createProduct(request)
S->>S: Read tenantId from TenantContext
loop Each requested SKU
S->>SR: existsByTenantIdAndSkuCode(tenantId, skuCode)
end
alt Duplicate SKU
S-->>C: BusinessException
else Unique SKUs
S->>PR: save(Product)
loop Each requested SKU
S->>SR: save(ProductSku)
end
S-->>C: Product
C-->>Manager: ApiResponse<Product>
end

```

## **3.6 Marketplace Product Import and Price Sync**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.6 Marketplace Product Import and Price Sync - Shopee
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class ShopeeCatalogController {
  +importProducts()
  +syncPrices()
}
class ShopeeProductImportService {
  +importForCurrentTenant()
  +importProducts(MarketplaceConnection)
}
class ShopeePriceSyncService {
  +syncForCurrentTenant()
  +sync(MarketplaceConnection)
}
class ShopeeCatalogClient {
  +getItemList(Long, int, int, String)
  +getItemBaseInfo(Long, List<Long>)
  +getModelList(Long, Long)
}
class CatalogMappingStore {
  +createProduct(String, String, boolean)
  +createSku(long, String, String, BigDecimal)
  +insertLink(...)
  +insertPriceHistory(...)
}
class PriceChangePolicy {
  +apply(LinkRef, BigDecimal, String)
}
ShopeeCatalogController --> ShopeeProductImportService
ShopeeProductImportService --> ShopeeCatalogClient
ShopeeProductImportService --> CatalogMappingStore
ShopeePriceSyncService --> ShopeeCatalogClient
ShopeePriceSyncService --> PriceChangePolicy
PriceChangePolicy --> CatalogMappingStore
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Admin
participant C as ShopeeCatalogController
participant I as ShopeeProductImportService
participant API as ShopeeCatalogClient
participant M as CatalogMappingStore
participant P as ShopeePriceSyncService
participant Policy as PriceChangePolicy
Admin->>C: importProducts()
C->>I: importForCurrentTenant()
I->>API: Fetch item and model pages
loop Each marketplace SKU
I->>M: Upsert catalog mapping
end
I-->>C: ImportReport
Admin->>C: syncPrices()
C->>P: syncForCurrentTenant()
P->>API: Fetch marketplace prices
loop Each linked SKU
P->>Policy: apply(link, newPrice, currency)
Policy->>M: Update price or record anomaly
end
P-->>C: Result

```

## **3.7 Inventory Management**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.7 Inventory Management - Implemented Outbox Path
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class InventoryController {
  +adjust(Long, int)
  +deduct(Long, int)
  +restock(Long, int)
}
class InventoryService {
  +adjustStock(Long, int)
  +deductStock(Long, int)
  +restock(Long, int)
}
class InventoryRepository {
  +findBySkuId(Long)
  +save(Inventory)
}
class InventoryOutboxEventRepository {
  +save(InventoryOutboxEvent)
}
class OutboxPublisherScheduler {
  +publishPendingEvents()
}
class InventoryOutboxEvent {
  +skuId
  +platform
  +quantity
  +status
}
InventoryController --> InventoryService
InventoryService --> InventoryRepository
InventoryService --> InventoryOutboxEventRepository
InventoryOutboxEventRepository --> InventoryOutboxEvent
OutboxPublisherScheduler --> InventoryOutboxEventRepository
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Manager
participant C as InventoryController
participant S as InventoryService
participant R as InventoryRepository
participant O as InventoryOutboxEventRepository
participant W as OutboxPublisherScheduler
Manager->>C: adjust(skuId, quantity)
C->>S: adjustStock(skuId, quantity)
S->>R: findBySkuId(skuId)
S->>R: save(Inventory)
S->>O: save(InventoryOutboxEvent for Shopee)
S-->>C: void
W->>O: findTop100ByStatusOrderByCreatedAtAsc(PENDING)
W->>O: save(event marked SENT)

```

## **3.8 Order Synchronization via Webhook**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.8 Order Synchronization via Webhook - Shopee
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class WebhookController {
  +receiveWebhook(Long, String, ..., String)
}
class MarketplaceConnectionRepository {
  +findByTenantIdAndPlatform(Long, Platform)
}
class ShopeeWebhookValidator {
  +isValidSignature(...)
  +isTimestampValid(Long)
}
class ShopeeWebhookDispatcher {
  +dispatch(Long, ShopeeWebhookEvent, String)
}
class RabbitTemplate {
  +convertAndSend(...)
}
class OrderMessageConsumer {
  +handleIncomingOrder(WebhookOrderPayload)
}
class OrderService {
  +processIncomingOrder(WebhookOrderPayload, OrderSource)
}
WebhookController --> MarketplaceConnectionRepository
WebhookController --> ShopeeWebhookValidator
WebhookController --> ShopeeWebhookDispatcher
ShopeeWebhookDispatcher --> RabbitTemplate
RabbitTemplate --> OrderMessageConsumer
OrderMessageConsumer --> OrderService
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Shopee
participant C as WebhookController
participant R as MarketplaceConnectionRepository
participant V as ShopeeWebhookValidator
participant D as ShopeeWebhookDispatcher
participant Q as RabbitMQ
participant O as OrderService
Shopee->>C: POST webhook(tenantId, SHOPEE, payload)
C->>R: findByTenantIdAndPlatform(...)
C->>V: isValidSignature(...)
alt Invalid signature or timestamp
C-->>Shopee: 401
else Valid webhook
C->>V: isTimestampValid(...)
C->>D: dispatch(tenantId, event, payload)
D->>Q: Publish order event
C-->>Shopee: 200 OK
Q->>O: processIncomingOrder(payload, source)
end

```

## **3.9 Order Synchronization via Polling**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.9 Order Synchronization via Polling - TikTok
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class OrderPollingScheduler {
  +pollOrdersScheduled()
  +pollOrdersFromMarketplaces()
  +pollOrdersForConnection(MarketplaceConnection, int)
}
class MarketplaceConnectionRepository {
  +findByStatus(ConnectionStatus)
}
class TiktokOrderClient {
  +searchOrders(..., OrderSearchRequest)
}
class TiktokOrderIngestionService {
  +ingestOrder(Long, TiktokOrderDetailResponse)
}
class MarketplaceConnection {
  +tenantId
  +platform
  +status
}
OrderPollingScheduler --> MarketplaceConnectionRepository
OrderPollingScheduler --> TiktokOrderClient
OrderPollingScheduler --> TiktokOrderIngestionService
MarketplaceConnectionRepository --> MarketplaceConnection
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
participant Timer
participant S as OrderPollingScheduler
participant R as MarketplaceConnectionRepository
participant API as TiktokOrderClient
participant I as TiktokOrderIngestionService
Timer->>S: pollOrdersScheduled()
S->>R: findByStatus(CONNECTED)
loop Each TikTok connection
S->>S: pollOrdersForConnection(connection, lookbackMinutes)
loop Each result page
S->>API: searchOrders(shop, OrderSearchRequest)
API-->>S: orders and nextPageToken
loop Each order
S->>I: ingestOrder(tenantId, order)
end
end
end

```

## **3.10 Order Lifecycle Management**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.10 Order Lifecycle Management
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class OrderController {
  +list(Pageable)
  +updateStatus(Long, OrderStatus)
}
class OrderService {
  +processIncomingOrder(...)
  +updateStatus(Long, OrderStatus)
  +updateOrderStatus(...)
  +handleOrderStatusChange(...)
}
class OrderRepository {
  +findById(Long)
  +save(Order)
}
class OrderStatusHistoryRepository {
  +save(OrderStatusHistory)
}
class InventoryServiceClient {
  +deductStock(Long, int)
  +restock(Long, int)
}
class Order {
  +tenantId
  +status
  +platformOrderId
}
OrderController --> OrderService
OrderService --> OrderRepository
OrderService --> OrderStatusHistoryRepository
OrderService --> InventoryServiceClient
OrderRepository --> Order
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Manager
participant C as OrderController
participant S as OrderService
participant R as OrderRepository
participant H as OrderStatusHistoryRepository
Manager->>C: updateStatus(orderId, status)
C->>S: updateStatus(orderId, status)
S->>R: findById(orderId)
S->>S: updateOrderStatus(order, status, source)
S->>R: save(order)
S->>H: save(OrderStatusHistory)
S-->>C: Order
C-->>Manager: ApiResponse<Order>

```

## **3.11 Shipment and Tracking Management**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.11 Shipment and Tracking Management
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class ShopeeLogisticsController {
  +shipOrder(...)
}
class ShopeeLogisticsService {
  +confirmShipment(MarketplaceConnection, String, ShipOrderRequest)
  +getShippingParameter(MarketplaceConnection, String)
}
class OrderController {
  +updateTracking(UpdateOrderTrackingRequest)
  +track(String, String)
}
class OrderService {
  +updateOrderTracking(UpdateOrderTrackingRequest)
  +trackOrder(String, String)
}
class OrderRepository {
  +findByTenantIdAndPlatformAndPlatformOrderId(...)
  +save(Order)
}
ShopeeLogisticsController --> ShopeeLogisticsService
OrderController --> OrderService
OrderService --> OrderRepository
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Manager
participant C as OrderController
participant S as OrderService
participant R as OrderRepository
Manager->>C: updateTracking(UpdateOrderTrackingRequest)
C->>S: updateOrderTracking(request)
S->>R: Find order by tenant and marketplace ID
S->>S: Apply tracking and shipment data
S->>R: save(order)
S-->>C: Order
C-->>Manager: ApiResponse<Order>

```

## **3.12 Return Refund and Dispute Management**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.12 Return Refund and Dispute Management - Implemented Paths
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class ReturnRefundController {
  +create(CreateReturnRequest)
  +approve(Long)
}
class ReturnRefundService {
  +createReturnRequest(CreateReturnRequest)
  +approveAndRestock(Long)
}
class ReturnRefundRepository {
  +findById(Long)
  +save(ReturnRefund)
}
class OrderRepository {
  +findById(Long)
  +save(Order)
}
class ShopeeFinanceAndReturnsController {
  +confirmReturn(String)
  +disputeReturn(...)
}
class ShopeeFinanceAndReturnsService {
  +confirmReturn(...)
  +disputeReturn(...)
}
ReturnRefundController --> ReturnRefundService
ReturnRefundService --> ReturnRefundRepository
ReturnRefundService --> OrderRepository
ShopeeFinanceAndReturnsController --> ShopeeFinanceAndReturnsService
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Manager
participant C as ReturnRefundController
participant S as ReturnRefundService
participant O as OrderRepository
participant R as ReturnRefundRepository
Manager->>C: create(CreateReturnRequest)
C->>S: createReturnRequest(request)
S->>O: findById(orderId)
S->>R: save(ReturnRefund status REQUESTED)
S-->>C: ReturnRefund
Manager->>C: approve(refundId)
C->>S: approveAndRestock(refundId)
S->>R: findById(refundId)
S->>R: save(ReturnRefund status COMPLETED)
S->>O: save(Order status RETURNED)
S-->>C: ReturnRefund

```

## **3.13 AI Demand Forecasting and Product Recommendation**

### **PlantUML class diagram**

```plantuml

@startuml
title 3.13 AI Demand Forecasting and Product Recommendation
skinparam backgroundColor #FFFFFF
skinparam classAttributeIconSize 0
skinparam shadowing false
left to right direction
class DemandForecastController {
  +runForecast()
  +getForecasts(Long)
}
class DemandForecastService {
  +runForecastForTenant(Long)
  +getForecastsForSku(Long, Long)
}
class RecommendationController {
  +getRecommendations(Long)
}
class RecommendationService {
  +getTopRecommendations(Long, Long)
  +refreshRecommendations(Long, Long)
}
class PythonAiServiceClient {
  +requestDemandForecast(Long)
  +requestRecommendations(Long, Long)
}
class DemandForecastRepository {
  +save(DemandForecast)
  +findByTenantIdAndSkuId(Long, Long)
}
class ProductRecommendationCacheRepository {
  +save(ProductRecommendationCache)
  +findTop3BySourceSkuIdOrderBySimilarityScoreDesc(Long)
}
DemandForecastController --> DemandForecastService
RecommendationController --> RecommendationService
DemandForecastService --> PythonAiServiceClient
RecommendationService --> PythonAiServiceClient
DemandForecastService --> DemandForecastRepository
RecommendationService --> ProductRecommendationCacheRepository
@enduml

```

### **Mermaid sequenceDiagram**

```mermaid

sequenceDiagram
actor Manager
participant FC as DemandForecastController
participant FS as DemandForecastService
participant RC as RecommendationController
participant RS as RecommendationService
participant Python as PythonAiServiceClient
participant DB as AI repositories
Manager->>FC: runForecast()
FC->>FS: runForecastForTenant(tenantId)
FS->>Python: requestDemandForecast(tenantId)
Python-->>FS: ForecastResultDto list
FS->>DB: Compare model RMSE and save version
opt New model is better
FS->>DB: Save DemandForecast rows
end
Manager->>RC: getRecommendations(skuId)
RC->>RS: getTopRecommendations(tenantId, skuId)
RS->>DB: Read cached top 3
opt Cache empty
RS->>Python: requestRecommendations(tenantId, skuId)
RS->>DB: Save ProductRecommendationCache rows
end
RS-->>RC: Recommendations
```
