# Payment API

## Base URL

| Environment | Server Port | Context Path | Actuator Port |
|-------------|-------------|--------------|---------------|
| local | 8080 | `/api/v1` | 8081 |

All API endpoints are under `http://localhost:8080/api/v1`.

## Endpoints

### 1. Create Payment (Charge)

```
POST /api/v1/payments
```

Creates a payment, submits it to the simulated provider, and applies the result synchronously. The entire charge lifecycle (create → submit → apply result) happens within a single API call.

#### Request Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | `application/json` |
| `X-Correlation-Id` | No | Client-provided UUID for tracing. If missing or invalid, a UUID is generated. |
| `X-Base-Url` | No | Base URL for HATEOAS links (defaults to `/api/v1`). |

#### Request Body

```json
{
  "merchantId": "m_acme",
  "customerRef": "cust_123",
  "billRef": "INV-001",
  "amount": "1250.00",
  "currency": "INR",
  "paymentMethod": "UPI",
  "paymentToken": "success:test"
}
```

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `merchantId` | String | Yes | Not blank, ≤ 255 chars |
| `customerRef` | String | Yes | Not blank, ≤ 255 chars |
| `billRef` | String | Yes | Not blank, ≤ 255 chars |
| `amount` | String | Yes | Not blank, ≥ 0.01, decimal string |
| `currency` | String | Yes | Not blank, pattern `[A-Z]{3}` (ISO-4217) |
| `paymentMethod` | String | Yes | Not blank, one of: UPI, CREDIT_CARD, DEBIT_CARD, NET_BANKING |
| `paymentToken` | String | Yes | Not blank, ≤ 500 chars |

> **Security:** The `paymentToken` is accepted in the request but never echoed back in any response.

#### Response: 201 Created

```json
{
  "paymentId": "07bea8c0-1c23-4e81-87d0-8596f794fdd5",
  "merchantId": "m_acme",
  "customerRef": "cust_123",
  "billRef": "INV-001",
  "amount": 1250.00,
  "currency": "INR",
  "paymentMethod": "UPI",
  "status": "SUCCEEDED",
  "providerReference": "provider_txn_550e8400-e29b-41d4-a716-446655440000",
  "failureCode": null,
  "failureReason": null,
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "createdAt": "2026-09-14T04:10:05.472382Z",
  "updatedAt": "2026-09-14T04:10:05.500419Z",
  "links": {
    "self": "/api/v1/payments/07bea8c0-1c23-4e81-87d0-8596f794fdd5",
    "billPayments": "/api/v1/payments/by-bill/INV-001"
  }
}
```

Fields are omitted (`@JsonInclude(NON_NULL)`) when null.

#### Response: 400 Bad Request (Validation)

```json
{
  "timestamp": 1789359102,
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/v1/payments",
  "details": [
    {"field": "amount", "message": "must be greater than or equal to 0.01"}
  ],
  "correlationId": "unknown"
}
```

#### Response: 400 Bad Request (Business)

```json
{
  "timestamp": 1789359102,
  "status": 400,
  "error": "INVALID_REQUEST",
  "message": "Unsupported currency: XYZ",
  "path": "/api/v1/payments",
  "details": null,
  "correlationId": "unknown"
}
```

---

### 2. Get Payment by ID

```
GET /api/v1/payments/{paymentId}
```

#### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `paymentId` | UUID | Internal payment identifier (from the response `paymentId` field). |

#### Response: 200 OK

Same structure as the create response, with the current `status` and provider fields.

#### Response: 404 Not Found

```json
{
  "timestamp": 1789359102,
  "status": 404,
  "error": "NOT_FOUND",
  "message": "Payment not found: 00000000-0000-0000-0000-000000000000",
  "path": "/api/v1/payments/00000000-0000-0000-0000-000000000000",
  "correlationId": "a1b2c3d4-..."
}
```

---

### 3. Get Payments by Bill Reference

```
GET /api/v1/payments/by-bill/{billReference}?merchantId={merchantId}
```

Returns all payments for a given bill reference and merchant.

#### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `billReference` | String | ERP bill reference. |

#### Query Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `merchantId` | String | Yes | Merchant identifier for tenant scoping. |

#### Response: 200 OK

```json
[
  {
    "paymentId": "...",
    "merchantId": "m_acme",
    "billRef": "INV-001",
    "amount": 1250.00,
    "currency": "INR",
    "status": "SUCCEEDED",
    ...
  }
]
```

---

## Internal Health Endpoint

```
GET /api/v1/internal/health
```

Lightweight technical check — does not probe external infrastructure.

```json
{
  "status": "UP",
  "applicationName": "payment-gateway-settlement-core",
  "timestamp": "2026-09-14T10:00:00Z",
  "correlationId": "none"
}
```

## Actuator Endpoints

Available on port 8081 at `/actuator`:

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Full health check (includes PostgreSQL, Redis). |
| `/actuator/info` | Application info. |
| `/actuator/metrics` | Application metrics. |
| `/actuator/prometheus` | Prometheus-format metrics. |

## Error Response Format

All errors return a structured `ApiError` JSON:

```json
{
  "timestamp": 1789359102,
  "status": <HTTP status code>,
  "error": "<ERROR_CODE>",
  "message": "<human-readable message>",
  "path": "<request path>",
  "details": <array of ValidationError or null>,
  "correlationId": "<correlation ID or 'unknown'>"
}
```

### Error Codes

| HTTP Status | Error Code | Condition |
|-------------|------------|-----------|
| 400 | `VALIDATION_FAILED` | Request body validation failed (missing/invalid fields). |
| 400 | `INVALID_REQUEST` | Business logic validation (unsupported currency, negative amount, etc.). |
| 404 | `NOT_FOUND` | Payment not found by ID. |
| 409 | `ILLEGAL_STATE_TRANSITION` | Invalid state machine transition (should not occur via API; only on race conditions). |
| 500 | `INTERNAL_ERROR` | Unexpected server error. |

> **Security:** Stack traces and database error messages are never exposed to clients. The `correlationId` allows clients and operators to correlate errors with server logs.
