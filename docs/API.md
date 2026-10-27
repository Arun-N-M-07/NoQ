# NoQ REST API

Base URL `http://localhost:8080` · JSON in, JSON out · all responses below were captured from the running app.

**Authentication**
- Customers need no login. They place an order and read it back (the confirmation screen's live status), proving
  ownership with the header `X-Pickup-Otp: <6-digit code>` (headers don't end up in browser history or URL logs).
  5 wrong codes lock that call for the order. NoQ has no payment API: the customer pays by hand at the counter.
- Kitchen and Staff endpoints use the login session (browser) or **HTTP Basic** (Postman): `kitchen / kitchen123`
  (KITCHEN) and `staff / staff123` (STAFF). Each role can only call its own endpoints (403 otherwise).

**Status codes used**

| Code | Meaning in NoQ |
|---|---|
| 200 OK | Read or update succeeded |
| 201 Created | Order / notification created (`Location` header points to it) |
| 400 Bad Request | Validation failed, malformed JSON, unknown menu item, wrong pickup OTP |
| 401 Unauthorized | Staff endpoint called without (valid) login |
| 403 Forbidden | Logged in but wrong role, or wrong/missing customer OTP |
| 404 Not Found | Order or menu item does not exist |
| 409 Conflict | Business rule: item not available, illegal status change, already collected, duplicate submission, concurrent update |
| 503 Service Unavailable | Database error |

**Error body** (always the same shape):

```json
{
  "timestamp": "2026-10-20T00:17:36.601919",
  "status": 409,
  "error": "Conflict",
  "message": "Garden Salad Bowl is not available right now",
  "path": "/api/orders",
  "fieldErrors": null
}
```

---

## Endpoint summary

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/api/menu` | public | Active menu (cached). `?category=SNACKS` filters |
| GET | `/api/menu/popular?limit=6` | public | Most popular orderable items (HQL query 1, cached) |
| GET | `/api/menu/{id}` | public | One item |
| PATCH | `/api/menu/{id}/availability` | KITCHEN | `{"available": true/false}` - the Kitchen page toggle. Menu items and prices are not edited through the API (they come from `data.sql`) |
| POST | `/api/orders` | public | Place order → 201 with reference, OTP and item summary (the confirmation screen) |
| GET | `/api/orders/{ref}` | OTP header or STAFF | Full order details incl. current status (never includes the OTP) - polled by the confirmation screen |
| GET | `/api/orders` | KITCHEN / STAFF | `?status=PREPARING` (Kitchen page) or `?status=READY_FOR_PICKUP` (Staff page) uses HQL query 2; without a status: all open orders |
| PATCH | `/api/orders/{ref}/status` | KITCHEN | `READY_FOR_PICKUP` (Kitchen button) or `CANCELLED` (only while PREPARING) |
| POST | `/api/orders/{ref}/pickup` | STAFF | Complete the pickup in ONE call: checks status and OTP, then saves COLLECTED (once) |
| GET | `/api/demo/hibernate-session` | STAFF | SessionFactory → Session → Transaction demo |
| GET | `/api/demo/spring-container` | STAFF | ApplicationContext / BeanFactory / proxy demo |
| GET | `/actuator/health` | public | Health check |
| GET | `/actuator/caches`, `/actuator/metrics/cache.gets?tag=cache:menu&tag=result:hit` | STAFF | Cache inspection |
| POST | `:8081/api/notifications/order-ready` | public (internal) | Microservice: receive "order ready" event |
| GET | `:8081/api/notifications` | public | Microservice: list received events |

---

## Menu

### List menu items
`GET /api/menu` · `GET /api/menu?category=BEVERAGES`

```json
[
  {
    "id": 3,
    "name": "Samosa (2 pcs)",
    "description": "Flaky pastry stuffed with spiced potato and peas, with mint chutney.",
    "category": "SNACKS",
    "categoryLabel": "Snacks",
    "categoryEmoji": "🥟",
    "price": 20.00,
    "imageUrl": "https://images.unsplash.com/photo-1601050690597-df0568f70950?w=600&h=400&fit=crop",
    "available": true,
    "active": true,
    "orderable": true,
    "popularityCount": 2
  }
]
```

`?category=PIZZAS` → **400** `Invalid value for parameter 'category'`.

### Popular items
`GET /api/menu/popular?limit=5` → same item shape, sorted by `popularityCount` (ties: curated menu order).

### Availability (KITCHEN)
`PATCH /api/menu/7/availability` with `{"available": false}` → **200** item (the Kitchen page toggle). `{}` → **400**
`fieldErrors.available`. As `staff` → **403**; without login → **401**.
A dish with `"available": false` stays on the menu (greyed out) and checkout rejects it with **409** `… is not available right now`.

---

## Orders

### Place an order
`POST /api/orders`

```json
{
  "items": [
    { "menuItemId": 1, "quantity": 2 },
    { "menuItemId": 7, "quantity": 1 }
  ],
  "idempotencyKey": "3f1c0d9e8a7b4c2d"
}
```

**201 Created** `Location: /api/orders/NQ-2026-DEBCE`

```json
{
  "orderReference": "NQ-2026-DEBCE",
  "pickupOtp": "900932",
  "status": "PREPARING",
  "totalAmount": 115.00,
  "message": "Order placed! Show your pickup code at the counter when collecting.",
  "items": [
    { "name": "Masala Dosa", "unitPrice": 50.00, "quantity": 2, "lineTotal": 100.00 },
    { "name": "Masala Chai", "unitPrice": 15.00, "quantity": 1, "lineTotal": 15.00 }
  ]
}
```

> This is the **only** response that ever contains the plain OTP; the cart page shows it as the order confirmation.
> Prices and totals come from the database; any `total`/`price` field sent by the client is ignored.

### Order errors

| Request | Response |
|---|---|
| `{"items":[{"menuItemId":1,"quantity":0}]}` | **400** `fieldErrors: {"items[0].quantity": "quantity must be at least 1"}` |
| `{oops` | **400** `Malformed JSON request or invalid field value` |
| item 9999 | **400** `Menu item 9999 does not exist` |
| item 22 (marked not available) | **409** `Garden Salad Bowl is not available right now` |
| item 23 (inactive) | **409** `Chilli Paneer is no longer on the menu` |
| item 12 × 20, repeated any number of times | **201** each time (there is no stock limit; at most 20 per item per order) |
| same `idempotencyKey` twice | **409** `This order was already submitted as NQ-2026-…` |
| many buyers order the same item at once | all **201** (no stock, so nothing to race for) |

### Order details (customer with OTP, or staff)
`GET /api/orders/NQ-2026-DEBCE` with header `X-Pickup-Otp: 900932` (or a staff login). The customer's open
confirmation screen calls this every 5 s and shows `status` (`PREPARING` → `READY_FOR_PICKUP` → `COLLECTED`).

```json
{
  "orderReference": "NQ-2026-DEBCE",
  "status": "PREPARING",
  "subtotal": 115.00,
  "totalAmount": 115.00,
  "createdAt": "2026-10-20T00:17:36.395372",
  "updatedAt": "2026-10-20T00:17:36.395377",
  "completedAt": null,
  "items": [
    { "name": "Masala Dosa", "unitPrice": 50.00, "quantity": 2, "lineTotal": 100.00 },
    { "name": "Masala Chai", "unitPrice": 15.00, "quantity": 1, "lineTotal": 15.00 }
  ]
}
```

No/wrong OTP → **403** `Invalid pickup code for this order`; after 5 wrong codes → **403** `Too many wrong pickup codes
for this order. Please ask at the counter.` Unknown reference → **404**.

### List orders (KITCHEN / STAFF)
`GET /api/orders?status=PREPARING` (Kitchen) · `GET /api/orders?status=READY_FOR_PICKUP` (Staff) (HQL: `select o from CustomerOrder o where o.status = :status order by o.createdAt desc`) → array of order details.

### Change status (KITCHEN)
`PATCH /api/orders/NQ-2026-DEBCE/status` `{"status": "READY_FOR_PICKUP"}` → **200** order details.
Life cycle: `PREPARING → READY_FOR_PICKUP → COLLECTED`.

| Attempt | Response |
|---|---|
| READY_FOR_PICKUP twice | **409** `Cannot change order from READY_FOR_PICKUP to READY_FOR_PICKUP` |
| → COLLECTED | **400** `Orders are collected by verifying the customer's pickup OTP (collect)` |
| CANCELLED when ready | **409** `Only orders still being prepared can be cancelled (current: READY_FOR_PICKUP)` |
| `{"status": "COOKING"}` | **400** `Malformed JSON request or invalid field value` |

Moving to **READY_FOR_PICKUP** also calls the notification microservice after the transaction commits.

### Complete pickup (STAFF)
`POST /api/orders/NQ-2026-DEBCE/pickup` `{"otp": "900932"}` → **200** with `"status": "COLLECTED"` and `completedAt`.
There is no separate "verify OTP" call: this one request checks everything and collects the order, or changes nothing.
The order row is locked (`SELECT ... FOR UPDATE`) first, so two staff cannot collect it twice. Any failed check leaves the order untouched:

| Situation (checked in this order) | Response |
|---|---|
| unknown order | **404** `Order NQ-2026-… not found` |
| already COLLECTED | **409** `Order already collected at 03:15 PM` |
| not READY_FOR_PICKUP | **409** `Order is not ready for pickup (status: PREPARING)` |
| wrong OTP | **400** `Invalid pickup OTP` |
| `{"otp": "12"}` (rejected before the service runs) | **400** `fieldErrors.otp: otp must be exactly 6 digits` |

### Cancel (KITCHEN, API only)
`PATCH /api/orders/{ref}/status` `{"status": "CANCELLED"}` while PREPARING → **200**; the dishes leave the popularity count exactly once,
Cancelling again → **409**.

---

## Viva demo endpoints (STAFF)

`GET /api/demo/hibernate-session`

```json
{
  "entitiesMappedBySessionFactory": ["CustomerOrder", "MenuItem", "OrderItem", "StaffUser"],
  "transactionActive": true,
  "sessionOpen": true,
  "transactionCommitted": true,
  "hqlActiveMenuItems": 22,
  "hqlTopThreeByPopularity": ["Masala Dosa", "Samosa (2 pcs)", "Masala Chai"]
}
```

`GET /api/demo/spring-container`

```json
{
  "applicationContextClass": "org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext",
  "applicationContextIsABeanFactory": true,
  "totalBeans": 461,
  "noqBeans": ["conceptDemoService", "customerOrderRepository", "demoRestController", "globalExceptionHandler", "..."],
  "orderServiceIsSingleton": true,
  "orderServiceIsAopProxy": true,
  "orderServiceProxyClass": "com.noq.service.OrderService$$SpringCGLIB$$0"
}
```

Cache metrics: `GET /actuator/metrics/cache.gets?tag=cache:menu&tag=result:hit` → `"measurements":[{"statistic":"COUNT","value":1.0}]`
(load the menu twice, the second call is a hit and logs no "Cache MISS" line).

---

## Notification microservice (port 8081)

`POST http://localhost:8081/api/notifications/order-ready`

```json
{ "orderReference": "NQ-2026-PZKJP", "eventType": "ORDER_READY" }
```

**201 Created**

```json
{
  "notificationId": "e70c9cf0-ed87-47dd-8f31-ca42f42f56f9",
  "orderReference": "NQ-2026-PZKJP",
  "eventType": "ORDER_READY",
  "status": "RECEIVED",
  "receivedAt": "2026-10-19T18:42:41.468249Z"
}
```

Invalid payload → **400**

```json
{
  "status": 400,
  "error": "Validation failed",
  "fieldErrors": {
    "orderReference": "must look like NQ-2026-AB12C",
    "eventType": "only ORDER_READY is supported"
  }
}
```

`GET http://localhost:8081/api/notifications` → list of acknowledgements, newest first (in memory, max 100).
