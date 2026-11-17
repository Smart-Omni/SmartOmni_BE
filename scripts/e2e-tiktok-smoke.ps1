param(
    [string]$SmartOmniBaseUrl = "http://localhost",
    [string]$SimulatorBaseUrl = "http://localhost:8090",
    [string]$TestAdminToken = "CHANGE_ME_test_admin_token",
    [string]$PostgresContainer = "smartomni-postgres",
    [string]$DbUser = "postgres",
    [string]$DbName = "smartomni"
)

$ErrorActionPreference = "Stop"
$results = [System.Collections.Generic.List[PSCustomObject]]::new()

function Add-Result {
    param([string]$Stage, [string]$Check, [string]$Status, [string]$Detail)
    $results.Add([PSCustomObject]@{
        Stage  = $Stage
        Check  = $Check
        Status = $Status
        Detail = $Detail
    })
    $color = if ($Status -eq "PASS") { "Green" } else { "Red" }
    Write-Host "[$Status] [$Stage] $Check -> $Detail" -ForegroundColor $color
}

function Parse-QueryString {
    param([string]$Query)
    $map = @{}
    if (-not $Query) { return $map }
    $q = $Query.TrimStart('?')
    foreach ($pair in $q -split '&') {
        if (-not $pair) { continue }
        $kv = $pair -split '=', 2
        $k = [System.Uri]::UnescapeDataString($kv[0])
        $v = if ($kv.Count -gt 1) { [System.Uri]::UnescapeDataString($kv[1]) } else { "" }
        $map[$k] = $v
    }
    return $map
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null
    )
    $curlArgs = @("-sS", "-X", $Method, $Uri, "-H", "Content-Type: application/json")
    foreach ($k in $Headers.Keys) {
        $curlArgs += "-H"
        $curlArgs += "${k}: $($Headers[$k])"
    }
    $tmpBody = $null
    try {
        if ($null -ne $Body) {
            $tmpBody = [System.IO.Path]::GetTempFileName()
            $json = ($Body | ConvertTo-Json -Depth 10 -Compress)
            [System.IO.File]::WriteAllText($tmpBody, $json, [System.Text.UTF8Encoding]::new($false))
            $curlArgs += "--data-binary"
            $curlArgs += "@$tmpBody"
        }
        $raw = & curl.exe @curlArgs -w "`n%{http_code}"
        $lines = $raw -split "`n"
        $status = [int]$lines[-1].Trim()
        $respBody = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
        if ($status -lt 200 -or $status -ge 300) {
            throw "[$Method $Uri] HTTP $status | BODY: $respBody"
        }
        if (-not $respBody) { return $null }
        return ($respBody | ConvertFrom-Json)
    } finally {
        if ($tmpBody) { Remove-Item -Path $tmpBody -ErrorAction SilentlyContinue }
    }
}

function Invoke-Sql {
    param([string]$Sql)
    $output = docker exec $PostgresContainer psql -U $DbUser -d $DbName -t -A -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "SQL failed: $output"
    }
    return ($output | Out-String).Trim()
}

try {
    # =========================================================================
    # STAGE 1: Auth & OAuth Simulator
    # =========================================================================
    $smokeEmail = "e2e.tiktok.smoke@smartomni.test"
    $smokePassword = "SmokeTestPassword123!"
    $smokeSubdomain = "e2esmoke"
    $jwt = $null
    $tenantId = $null

    try {
        $loginRes = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/auth/login" -Body @{
            email    = $smokeEmail
            password = $smokePassword
        }
        $jwt = if ($loginRes.data.accessToken) { $loginRes.data.accessToken } else { $loginRes.data.access_token }
        $tenantId = if ($loginRes.data.tenantId) { $loginRes.data.tenantId } elseif ($loginRes.data.user.tenantId) { $loginRes.data.user.tenantId } else { $loginRes.data.user.tenant_id }
    } catch {
        $onbRes = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/tenants/onboarding" -Body @{
            companyName     = "TikTok E2E Smoke Store"
            subdomain       = $smokeSubdomain
            admin_email     = $smokeEmail
            admin_full_name = "TikTok E2E Smoke Admin"
            password        = $smokePassword
        }
        $loginRes = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/auth/login" -Body @{
            email    = $smokeEmail
            password = $smokePassword
        }
        $jwt = if ($loginRes.data.accessToken) { $loginRes.data.accessToken } else { $loginRes.data.access_token }
        $tenantId = if ($loginRes.data.tenantId) { $loginRes.data.tenantId } elseif ($loginRes.data.user.tenantId) { $loginRes.data.user.tenantId } elseif ($loginRes.data.user.tenant_id) { $loginRes.data.user.tenant_id } else { $onbRes.data.id }
    }

    if ($jwt -and -not $tenantId) {
        $tenantId = Invoke-Sql "SELECT tenant_id FROM users WHERE email = '$smokeEmail' LIMIT 1;"
    }
    if (-not $jwt -or -not $tenantId) {
        throw "Failed to obtain SmartOmni JWT or tenantId"
    }
    Add-Result "Stage 1" "SmartOmni Auth" "PASS" "tenantId=$tenantId"

    $soHeaders = @{
        "Authorization" = "Bearer $jwt"
        "X-Tenant-Id"   = "$tenantId"
    }

    # Ensure Simulator seller & buyer sessions
    $identities = Invoke-Api -Method "GET" -Uri "$SimulatorBaseUrl/demo/identities"
    $seller = $identities.sellers | Select-Object -First 1
    $simShop = $seller.shops | Select-Object -First 1
    $buyer = $identities.buyers | Where-Object { $_.shopId -eq $simShop.id } | Select-Object -First 1

    $sellerSession = Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/sessions" -Body @{
        role     = "SELLER"
        sellerId = $seller.id
        shopId   = $simShop.id
    }
    $sellerHeaders = @{ "x-demo-session" = $sellerSession.sessionToken }

    $buyerSession = Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/sessions" -Body @{
        role    = "BUYER"
        buyerId = $buyer.id
        shopId  = $simShop.id
    }
    $buyerHeaders = @{ "x-demo-session" = $buyerSession.sessionToken }

    # Check if SmartOmni already has a CONNECTED TikTok shop for this tenant; otherwise run full OAuth flow
    $currentConn = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/marketplace-connections/tiktok-shop" -Headers $soHeaders
    if (-not $currentConn.data -or $currentConn.data.status -ne "CONNECTED") {
        $authStart = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/marketplace-connections/tiktok-shop/authorization" -Headers $soHeaders -Body @{
            redirectUri = "smartomni://oauth/tiktok"
        }
        $authUrl = [System.Uri]$authStart.data.authorizationUrl
        $queryPairs = Parse-QueryString $authUrl.Query
        $oauthState = $queryPairs["state"]
        $appKey = $queryPairs["app_key"]

        $consentRes = Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/authorization/consent" -Headers $sellerHeaders -Body @{
            app_key      = $appKey
            redirect_uri = "smartomni://oauth/tiktok"
            state        = $oauthState
            decision     = "accept"
        }
        $cbUri = [System.Uri]$consentRes.redirectUrl
        $cbQuery = Parse-QueryString $cbUri.Query
        $oauthCode = $cbQuery["code"]

        $cbRes = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/marketplace-connections/tiktok-shop/callback" -Headers $soHeaders -Body @{
            code  = $oauthCode
            state = $oauthState
        }
        if ($cbRes.data.status -ne "CONNECTED") {
            throw "Expected CONNECTED after OAuth callback, got $($cbRes.data.status)"
        }
        Add-Result "Stage 1" "OAuth Simulator -> SmartOmni" "PASS" "shopId=$($cbRes.data.shopId), status=CONNECTED"
        Start-Sleep -Seconds 2
    } else {
        Add-Result "Stage 1" "OAuth Simulator -> SmartOmni" "PASS" "Already CONNECTED shopId=$($currentConn.data.shopId)"
    }

    # =========================================================================
    # STAGE 2: WF-01 Auto-Import & Catalog Mapping
    # =========================================================================
    $autoImportRes = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/catalog/auto-import" -Headers $soHeaders
    if ($autoImportRes.data.status -ne "COMPLETED" -or $autoImportRes.data.totalProducts -le 0) {
        throw "Auto-import failed: $($autoImportRes | ConvertTo-Json -Compress)"
    }
    Add-Result "Stage 2" "WF-01 POST /catalog/auto-import" "PASS" "products=$($autoImportRes.data.totalProducts), skus=$($autoImportRes.data.totalSkus), imported=$($autoImportRes.data.importedCount), skipped=$($autoImportRes.data.skippedCount)"

    $catalogRes = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/catalog" -Headers $soHeaders
    $mappedItems = @($catalogRes.data | Where-Object { $_.mapped -eq $true })
    if ($mappedItems.Count -le 0) {
        throw "Expected mapped SKUs in GET /api/v1/tiktok/catalog"
    }
    $firstSku = $mappedItems[0]
    Add-Result "Stage 2" "D8 GET /catalog" "PASS" "totalItems=$($catalogRes.data.Count), mapped=$($mappedItems.Count), samplePrice=$($firstSku.currentPrice)"

    # =========================================================================
    # STAGE 3: Create Orders on Simulator -> D1 Lookup & D2 Sync (WF-02 15-day)
    # =========================================================================
    $simSkuId = $firstSku.tiktokSkuId

    $testAdminHeaders = @{ "x-test-admin-token" = $TestAdminToken }

    # Order 1: AWAITING_SHIPMENT (to test D3/D4/D5)
    $chk1 = Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/checkout" -Headers $buyerHeaders -Body @{
        checkoutKey = "e2e-ship-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
        items       = @(@{ skuId = $simSkuId; quantity = 1 })
    }
    $order1Id = $chk1.order.id
    Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/orders/$order1Id/pay" -Headers $buyerHeaders | Out-Null
    Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/test-admin/orders/$order1Id/transition" -Headers $testAdminHeaders -Body @{
        to     = "AWAITING_SHIPMENT"
        shopId = $simShop.id
        note   = "e2e skip hold"
    } | Out-Null
    $arr1 = Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/orders/$order1Id/arrange-shipment" -Headers $sellerHeaders -Body @{ handoverMethod = "PICKUP" }
    $package1Id = $arr1.package.externalId
    if (-not $package1Id) {
        $package1Id = $arr1.package.id
    }
    if (-not $package1Id) {
        $pkgDetail = Invoke-Api -Method "GET" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/orders/$order1Id" -Headers $sellerHeaders
        $package1Id = $pkgDetail.order.packageId
    }

    # Order 2: Advance to COMPLETED (to test D1, D2 & WF-02)
    $chk2 = Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/checkout" -Headers $buyerHeaders -Body @{
        checkoutKey = "e2e-comp-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
        items       = @(@{ skuId = $simSkuId; quantity = 2 })
    }
    $order2Id = $chk2.order.id
    Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/orders/$order2Id/pay" -Headers $buyerHeaders | Out-Null
    Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/test-admin/orders/$order2Id/transition" -Headers $testAdminHeaders -Body @{
        to     = "AWAITING_SHIPMENT"
        shopId = $simShop.id
        note   = "e2e skip hold"
    } | Out-Null
    Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/orders/$order2Id/arrange-shipment" -Headers $sellerHeaders -Body @{ handoverMethod = "PICKUP" } | Out-Null
    Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/orders/$order2Id/advance-shipment" -Headers $sellerHeaders | Out-Null
    Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/orders/$order2Id/advance-shipment" -Headers $sellerHeaders | Out-Null
    $ord2Detail = Invoke-Api -Method "GET" -Uri "$SimulatorBaseUrl/demo/shops/$($simShop.id)/orders/$order2Id" -Headers $sellerHeaders
    if ($ord2Detail.order.status -ne "COMPLETED") {
        Invoke-Api -Method "POST" -Uri "$SimulatorBaseUrl/test-admin/orders/$order2Id/transition" -Headers $testAdminHeaders -Body @{
            to     = "COMPLETED"
            shopId = $simShop.id
            note   = "e2e complete order"
        } | Out-Null
    }

    # D1: Lookup by orderId
    $d1Res = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/orders?orderId=$order2Id" -Headers $soHeaders
    if ($d1Res.data.orders.Count -ne 1 -or $d1Res.data.orders[0].order_id -ne $order2Id) {
        throw "D1 orderId lookup mismatch"
    }
    Add-Result "Stage 3" "D1 GET /orders?orderId=" "PASS" "orderId=$order2Id, status=$($d1Res.data.orders[0].order_status)"

    # D2: Sync orders into DB
    $d2Res = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/orders/sync" -Headers $soHeaders -Body @{}
    if ($d2Res.data.totalFetched -le 0) {
        throw "D2 sync returned 0 orders"
    }
    $dbDates = Invoke-Sql "SELECT id || '|' || COALESCE(completed_at::text,'') || '|' || COALESCE(eligible_for_points_at::text,'') || '|' || EXTRACT(EPOCH FROM (eligible_for_points_at - completed_at))::bigint FROM orders WHERE tenant_id = $tenantId AND platform_order_id = '$order2Id';"
    $parts = $dbDates -split '\|'
    if ($parts.Count -lt 4 -or [long]$parts[3] -ne 1296000) {
        throw "WF-02 15-day date check failed: $dbDates"
    }
    $order2DbId = $parts[0]
    Add-Result "Stage 3" "D2 POST /orders/sync (WF-02)" "PASS" "fetched=$($d2Res.data.totalFetched), created=$($d2Res.data.createdCount), updated=$($d2Res.data.updatedCount), delta=15d"

    # =========================================================================
    # STAGE 4: D3, D4, D5 Fulfillment & Shipping Label PDF
    # =========================================================================
    $d3Res = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/shipping-providers" -Headers $soHeaders
    if ($d3Res.data.Count -le 0 -or -not $d3Res.data[0].shippingProviderId) {
        throw "D3 shipping-providers returned empty"
    }
    Add-Result "Stage 4" "D3 GET /shipping-providers" "PASS" "provider=$($d3Res.data[0].shippingProviderId)"

    $d4Res = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/packages/$package1Id/ship" -Headers $soHeaders -Body @{
        handoverMethod    = "PICKUP"
        shippingServiceId = "SIM-CARRIER-1"
    }
    Add-Result "Stage 4" "D4 POST /packages/$package1Id/ship" "PASS" "shipped packageId=$package1Id"

    $pdfTmp = [System.IO.Path]::GetTempFileName()
    try {
        Invoke-WebRequest -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/packages/$package1Id/shipping-label/download" -Headers $soHeaders -OutFile $pdfTmp | Out-Null
        $bytes = [System.IO.File]::ReadAllBytes($pdfTmp)
        $headerStr = [System.Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(4, $bytes.Length))
        if ($headerStr -ne "%PDF") {
            throw "Expected %PDF header, got '$headerStr'"
        }
        Add-Result "Stage 4" "D5 GET shipping-label (PDF)" "PASS" "bytes=$($bytes.Length), header=$headerStr"
    } finally {
        Remove-Item -Path $pdfTmp -ErrorAction SilentlyContinue
    }

    # Negative D5 check: invalid packageId must return 404 JSON SHIPPING_LABEL_UNAVAILABLE
    $d5ErrorStatus = 0
    $d5ErrorCode = ""
    try {
        Invoke-WebRequest -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/packages/INVALID-PKG-999/shipping-label/download" -Headers $soHeaders | Out-Null
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $d5ErrorStatus = [int]$resp.StatusCode
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                $errJson = $_.ErrorDetails.Message | ConvertFrom-Json
                $d5ErrorCode = $errJson.code
            } else {
                $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
                $errJson = $reader.ReadToEnd() | ConvertFrom-Json
                $d5ErrorCode = $errJson.code
            }
        }
    }
    if ($d5ErrorStatus -ne 404 -or $d5ErrorCode -ne "SHIPPING_LABEL_UNAVAILABLE") {
        throw "Expected 404 SHIPPING_LABEL_UNAVAILABLE for invalid packageId, got status=$d5ErrorStatus code=$d5ErrorCode"
    }
    Add-Result "Stage 4" "D5 Negative (Invalid packageId)" "PASS" "HTTP=$d5ErrorStatus, code=$d5ErrorCode"

    # =========================================================================
    # STAGE 5: D6, D7, D9 Aftersale
    # =========================================================================
    $d6Res = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/cancellations" -Headers $soHeaders
    Add-Result "Stage 5" "D6 GET /cancellations" "PASS" "count=$($d6Res.data.cancellations.Count)"

    $d7Res = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/returns?pageSize=10" -Headers $soHeaders
    Add-Result "Stage 5" "D7 GET /returns" "PASS" "count=$($d7Res.data.returns.Count)"

    $d9Reasons = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/aftersale/reasons" -Headers $soHeaders
    if ($d9Reasons.data.rejectReasons.Count -lt 5 -or $d9Reasons.data.returnStatuses.Count -lt 8) {
        throw "D9 reasons catalog incomplete"
    }
    Add-Result "Stage 5" "D9 GET /aftersale/reasons" "PASS" "rejectReasons=$($d9Reasons.data.rejectReasons.Count), returnStatuses=$($d9Reasons.data.returnStatuses.Count)"

    $d9Reject = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/aftersale/reject-reasons" -Headers $soHeaders
    Add-Result "Stage 5" "D9 GET /aftersale/reject-reasons" "PASS" "count=$($d9Reject.data.Count)"

    $d9Elig = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/aftersale/eligibility/$order2Id" -Headers $soHeaders
    Add-Result "Stage 5" "D9 GET /aftersale/eligibility/$order2Id" "PASS" "requestTypes=$($d9Elig.data.request_types.Count)"

    # =========================================================================
    # STAGE 6: WF-02 Voucher & 15-day Loyalty Points Idempotency
    # =========================================================================
    $vchCreate = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/vouchers" -Headers $soHeaders -Body @{
        title             = "E2E Smoke Voucher 15%"
        discountType      = "PERCENT_OFF"
        discountValue     = 15
        maxDiscountAmount = 50000
        minOrderAmount    = 100000
        usageLimit        = 20
    }
    if (-not $vchCreate.data.tiktokVoucherId) {
        throw "Voucher creation did not return tiktokVoucherId"
    }
    Add-Result "Stage 6" "WF-02 POST /vouchers" "PASS" "code=$($vchCreate.data.voucherCode)"

    $vchList = Invoke-Api -Method "GET" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/vouchers" -Headers $soHeaders
    Add-Result "Stage 6" "WF-02 GET /vouchers" "PASS" "count=$($vchList.data.Count)"

    $vchSync = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/tiktok/vouchers/sync" -Headers $soHeaders
    Add-Result "Stage 6" "WF-02 POST /vouchers/sync" "PASS" "synced=$($vchSync.data.Count)"

    # Backdate completed_at & eligible_for_points_at to 1 minute ago and reset points_awarded_at if needed
    Invoke-Sql "UPDATE orders SET completed_at = now() - interval '16 days', eligible_for_points_at = now() - interval '1 minute', points_awarded_at = NULL, points_awarded = NULL WHERE tenant_id = $tenantId AND id = $order2DbId;" | Out-Null
    Invoke-Sql "DELETE FROM order_loyalty_ledger WHERE tenant_id = $tenantId AND order_id = $order2DbId;" | Out-Null

    $award1 = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/orders/loyalty/award-eligible" -Headers $soHeaders
    if ($award1.data.ordersAwarded -lt 1 -or $award1.data.totalPointsAwarded -le 0) {
        throw "Expected loyalty points awarded on run 1, got $($award1 | ConvertTo-Json -Compress)"
    }
    Add-Result "Stage 6" "WF-02 Loyalty Award Run 1" "PASS" "awarded=$($award1.data.ordersAwarded), points=$($award1.data.totalPointsAwarded)"

    $award2 = Invoke-Api -Method "POST" -Uri "$SmartOmniBaseUrl/api/v1/orders/loyalty/award-eligible" -Headers $soHeaders
    if ($award2.data.ordersAwarded -ne 0 -or $award2.data.totalPointsAwarded -ne 0) {
        throw "Expected idempotent 0 points on run 2, got $($award2 | ConvertTo-Json -Compress)"
    }
    Add-Result "Stage 6" "WF-02 Loyalty Award Run 2 (Idempotent)" "PASS" "awarded=$($award2.data.ordersAwarded), points=$($award2.data.totalPointsAwarded)"

    Write-Host "`n================ E2E SMOKE SUMMARY ================" -ForegroundColor Cyan
    $results | Format-Table -AutoSize
} catch {
    Add-Result "ERROR" "Unhandled Failure" "FAIL" $_.Exception.Message
    $results | Format-Table -AutoSize
    exit 1
}
