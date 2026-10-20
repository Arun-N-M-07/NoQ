# NoQ REST API

Base URL `http://localhost:8080` · JSON in, JSON out · all responses below were captured from the running app.

**Authentication**
- Customer endpoints need no login. Endpoints about *one* order prove ownership with the header
  `X-Pickup-Otp: <6-digit code>` (headers don't end up in browser history or URL logs).
- Staff endpoints use the login session (browser) or **HTTP Basic** (Postman): `kitchen / kitchen123` (STAFF),
  `admin / admin123` (ADMIN).

**Status codes used**

| Code | Meaning in NoQ |
|---|---|
| 200 OK | Read or update succeeded |
| 201 Created | Order / menu item / notification created (`Location` header points to it) |
| 204 No Content | Menu item deactivated |
| 400 Bad Request | Validation failed, malformed JSON, unknown menu item, wrong pickup OTP, wrong payment type |
| 401 Unauthorized | Staff endpoint called without (valid) login |
| 403 Forbidden | Logged in but wrong role, or wrong/missing customer OTP |
| 404 Not Found | Order or menu item does not exist |
| 409 Conflict | Business rule: sold out, not enough stock, illegal status change, already paid, already collected, duplicate submission, concurrent update |
| 503 Service Unavailable | Database error |

**Error body** (always the same shape):

```json
{
  "timestamp": "2026-10-20T00:17:36.601919",
  "status": 409,
  "error": "Conflict",
  "message": "Only 15 x Tandoori Chicken (Half) left",
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
| POST | `/api/menu` | ADMIN | Create item → 201 |
| PUT | `/api/menu/{id}` | ADMIN | Replace item details (name, description, category, price, image, on-sale flag). **Stock is not changed by PUT** - use PATCH `/availability` |
| PATCH | `/api/menu/{id}/availability` | ADMIN | Change stock and/or sold-out flag |
| DELETE | `/api/menu/{id}` | ADMIN | Deactivate (soft delete) → 204 |
| POST | `/api/orders` | public | Place order → 201 with reference + OTP |
| GET | `/api/orders/{ref}/status` | public | Small status object for polling |
| GET | `/api/orders/{ref}` | OTP header or STAFF | Full order details (never includes the OTP) |
| POST | `/api/orders/{ref}/cancel` | OTP header | Customer cancels while still PLACED |
| GET | `/api/orders` | STAFF | All open orders (PLACED…READY), newest first; `?status=…` uses HQL query 2 and also accepts COMPLETED/CANCELLED |
| PATCH | `/api/orders/{ref}/status` | STAFF | Move order to CONFIRMED / PREPARING / READY / CANCELLED |
| POST | `/api/orders/{ref}/verify-pickup` | STAFF | Check OTP, mark COMPLETED (once) |
| POST | `/api/orders/{ref}/payments/counter-confirmation` | STAFF | Cash/UPI received at counter |
| POST | `/api/orders/{ref}/payments/mock-online` | OTP header | **Simulated** online payment |
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
    "availableQuantity": 58,
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

### Create item (ADMIN)
`POST /api/menu`

```json
{
  "name": "Vada Pav",
  "description": "Mumbai-style potato fritter in a pav with chutneys.",
  "category": "SNACKS",
  "price": 25.00,
  "imageUrl": "",
  "availableQuantity": 40,
  "available": true
}
```

**201 Created**, `Location: /api/menu/24`, body = the new item.

Validation failure → **400**:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/menu",
  "fieldErrors": {
    "name": "Name is required",
    "price": "Price must be at least 1.00",
    "category": "Category is required",
    "availableQuantity": "Stock cannot be negative"
  }
}
```

Without login → **401** `"Staff login required"` · as `kitchen` (STAFF) → **403** `"You do not have permission for this action"`.

### Update stock / sold out (ADMIN)
`PATCH /api/menu/24/availability` with `{"availableQuantity": 5}` or `{"available": false}` → **200** item.
Both fields missing → **400**.

### Deactivate (ADMIN)
`DELETE /api/menu/24` → **204**. The row stays (old orders reference it); it just disappears from the menu.

---

## Orders

### Place an order (counter payment)
`POST /api/orders`

```json
{
  "items": [
    { "menuItemId": 1, "quantity": 2 },
    { "menuItemId": 7, "quantity": 1 }
  ],
  "paymentMethod": "PAY_AT_COUNTER",
  "idempotencyKey": "3f1c0d9e8a7b4c2d"
}
```

**201 Created** `Location: /api/orders/NQ-2026-DEBCE`

```json
{
  "orderReference": "NQ-2026-DEBCE",
  "pickupOtp": "900932",
  "status": "PLACED",
  "paymentMethod": "PAY_AT_COUNTER",
  "paymentStatus": "PENDING",
  "totalAmount": 115.00,
  "message": "Order placed! Pay ₹115.00 at the counter. Show your pickup code when collecting."
}
```

> This is the **only** response that ever contains the plain OTP. Prices and totals come from the database;
> any `total`/`price` field sent by the client is ignored.

### Place an order (simulated online payment)
Same request with `"paymentMethod": "ONLINE"` → 201 with message
`"Order placed. Complete the (simulated) online payment within 15 minutes, otherwise the order is cancelled automatically."`

An ONLINE order still unpaid (PENDING or FAILED) and still PLACED after `noq.orders.online-payment-timeout` (15 min) is
cancelled by a scheduled job (every minute): stock and popularity are restored exactly once.

### Order errors

| Request | Response |
|---|---|
| `{"items":[{"menuItemId":1,"quantity":0}]}` | **400** `fieldErrors: {"items[0].quantity": "quantity must be at least 1", "paymentMethod": "paymentMethod is required (PAY_AT_COUNTER or ONLINE)"}` |
| `{oops` | **400** `Malformed JSON request or invalid field value` |
| item 9999 | **400** `Menu item 9999 does not exist` |
| item 16 (stock 0) | **409** `Margherita Pizza is sold out` |
| item 23 (inactive) | **409** `Chilli Paneer is no longer on the menu` |
| item 12 × 20 | **409** `Only 15 x Tandoori Chicken (Half) left` |
| same `idempotencyKey` twice | **409** `This order was already submitted as NQ-2026-…` |
| two buyers race for the last unit | one **201**, the other **409** "sold out" (menu rows are locked, so buyers queue up) |

### Poll status (public)
`GET /api/orders/NQ-2026-DEBCE/status`

```json
{
  "orderReference": "NQ-2026-DEBCE",
  "status": "PLACED",
  "paymentMethod": "PAY_AT_COUNTER",
  "paymentStatus": "PENDING",
  "totalAmount": 115.00,
  "updatedAt": "2026-10-20T00:17:36.395377"
}
```

### Order details (customer with OTP, or staff)
`GET /api/orders/NQ-2026-DEBCE` with header `X-Pickup-Otp: 900932`

```json
{
  "orderReference": "NQ-2026-DEBCE",
  "status": "PLACED",
  "paymentMethod": "PAY_AT_COUNTER",
  "paymentStatus": "PENDING",
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

No/wrong OTP → **403** `Invalid pickup code for this order`. After 5 wrong codes → **403**
`Too many wrong pickup codes for this order. Please ask at the counter.`

### List orders (STAFF)
`GET /api/orders` (all open orders) · `GET /api/orders?status=PLACED` (HQL: `select o from CustomerOrder o where o.status = :status order by o.createdAt desc`) → array of order details.

### Change status (STAFF)
`PATCH /api/orders/NQ-2026-DEBCE/status` `{"status": "CONFIRMED"}` → **200** order details.

| Attempt | Response |
|---|---|
| PLACED → READY | **409** `Cannot change order from PLACED to READY` |
| → COMPLETED | **400** `Orders are completed by verifying the customer's pickup OTP (verify-pickup)` |
| CONFIRMED an unpaid ONLINE order | **409** `Online payment has not been completed for this order yet` |
| CANCELLED while PREPARING | **409** `Only PLACED or CONFIRMED orders can be cancelled (current: PREPARING)` |
| `{"status": "COOKING"}` | **400** `Malformed JSON request or invalid field value` |

Moving to **READY** also calls the notification microservice after the transaction commits.

### Verify pickup (STAFF)
`POST /api/orders/NQ-2026-DEBCE/verify-pickup` `{"otp": "900932"}` → **200** with `"status": "COMPLETED"` and `completedAt`.

| Situation | Response |
|---|---|
| order not READY | **409** `Order is not ready for pickup (status: PREPARING)` |
| counter order not paid | **409** `Payment pending: collect ₹115.00 at the counter first` |
| wrong OTP | **400** `Invalid pickup OTP` |
| `{"otp": "12"}` | **400** `fieldErrors.otp: otp must be exactly 6 digits` |
| second time | **409** `Order already collected at 12:20 am` |

### Customer cancel
`POST /api/orders/{ref}/cancel` with `X-Pickup-Otp` → **200** `"status": "CANCELLED"`; stock returns to the menu.
After the kitchen confirmed → **409** `Your order has already been accepted by the kitchen. Please ask at the counter.`

---

## Payments

### Counter payment received (STAFF)
`POST /api/orders/NQ-2026-DEBCE/payments/counter-confirmation` (no body)

```json
{
  "orderReference": "NQ-2026-DEBCE",
  "paymentType": "CASH",
  "paymentStatus": "PAID",
  "amount": 115.00,
  "transactionReference": null,
  "message": "Counter payment received"
}
```

Again → **409** `Payment already received for this order`. On an ONLINE order → **400**.

### Simulated online payment (customer)
`POST /api/orders/{ref}/payments/mock-online` with `X-Pickup-Otp` and `{"success": false}`:

```json
{
  "orderReference": "NQ-2026-7D423",
  "paymentType": "ONLINE",
  "paymentStatus": "FAILED",
  "amount": 90.00,
  "transactionReference": "MOCK-629BAD21-332",
  "message": "Simulated payment failed: payment declined"
}
```

With `{"success": true}` → `"paymentStatus": "PAID"`, `"message": "Simulated payment successful"`.
Paying an already-paid order → **409**. Paying after the 15-minute window → **409** `The payment window for this order has
closed; it will be cancelled. Please order again.`; once expired → **409** `Order is cancelled`.
Every attempt is stored as an `OnlinePayment` row.

> **No real money moves.** No card, UPI PIN or bank data is requested or stored.

---

## Viva demo endpoints (STAFF)

`GET /api/demo/hibernate-session`

```json
{
  "entitiesMappedBySessionFactory": ["CashPayment", "CustomerOrder", "MenuItem", "OnlinePayment", "OrderItem", "Payment", "StaffUser"],
  "transactionActive": true,
  "sessionOpen": true,
  "transactionCommitted": true,
  "hqlActiveMenuItems": 22,
  "hqlTopThreeByPopularity": ["Masala Dosa", "Samosa (2 pcs)", "Masala Chai"],
  "hqlPaymentsBySubclass": { "CashPayment": 2, "OnlinePayment": 2 }
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
