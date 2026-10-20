# NoQ — Architecture

## 1. System overview

```mermaid
flowchart LR
    subgraph Browser
        C["Customer pages<br/>menu · cart · track · demo pay<br/>(Thymeleaf HTML + vanilla JS)"]
        S["Staff pages<br/>order board · menu management"]
        P["Postman"]
    end

    subgraph APP["noq-app  (Spring Boot, port 8080)"]
        direction TB
        SEC["Spring Security filter chain<br/>form login · HTTP Basic · roles"]
        MVC["@Controller<br/>HomeController · StaffController · StaffMenuController"]
        REST["@RestController<br/>Menu · Order · Payment · Demo"]
        ADV["@RestControllerAdvice<br/>GlobalExceptionHandler"]
        SVC["@Service (@Transactional)<br/>MenuService · OrderService · PaymentService<br/>PickupCodeService · NotificationClient"]
        AOP["LoggingAspect (@Around)"]
        CACHE[("Caffeine cache<br/>menu · popularItems")]
        REPO["Spring Data JPA repositories<br/>+ HQL @Query"]
        HIB["Hibernate ORM<br/>(SessionFactory / Session)"]
        GW["PaymentGateway<br/>MockPaymentGateway"]
    end

    DB[("MySQL 8<br/>noq_db")]
    NS["noq-notification-service<br/>(Spring Boot, port 8081)<br/>POST /api/notifications/order-ready"]

    C -- "HTTP GET pages" --> SEC
    C -- "fetch() JSON" --> SEC
    S --> SEC
    P -- "JSON + Basic auth" --> SEC
    SEC --> MVC
    SEC --> REST
    REST -.errors.-> ADV
    MVC --> SVC
    REST --> SVC
    AOP -. wraps .-> SVC
    SVC <--> CACHE
    SVC --> REPO --> HIB --> DB
    SVC --> GW
    SVC -- "RestClient POST (after commit)" --> NS
```

**Monolith + one microservice.** The customer site, staff dashboard and REST API are one deployable
Spring Boot application (a *modular monolith*). Notification sending is split out into a separate service with
its own process, port and build, talking to the main app only through HTTP/JSON.

## 2. Directory structure

```
noq-app/src/main/java/com/noq
├── NoqApplication.java              @SpringBootApplication + @EnableCaching
├── aspect/LoggingAspect.java        AOP: timing + failure logging for every service method
├── controller/                      MVC page controllers (return template names)
│   ├── HomeController.java          /  /cart  /track  /pay/{ref}
│   ├── StaffController.java         /staff/login  /staff
│   └── StaffMenuController.java     /staff/menu/** (forms with @Valid + BindingResult)
├── restcontroller/                  JSON API
│   ├── MenuRestController.java      /api/menu/**
│   ├── OrderRestController.java     /api/orders/**
│   ├── PaymentRestController.java   /api/orders/{ref}/payments/**
│   └── DemoRestController.java      /api/demo/** (viva demos)
├── service/
│   ├── MenuService.java             menu CRUD, @Cacheable, cache eviction after commit
│   ├── OrderService.java            checkout, status transitions, cancel, pickup, @Scheduled expiry
│   ├── PaymentService.java          counter + simulated online payments
│   ├── PickupCodeService.java       SecureRandom OTP, BCrypt hash, attempt limiter
│   ├── PaymentGateway.java          seam for a real gateway
│   ├── MockPaymentGateway.java      SIMULATED gateway
│   ├── OrderReadyEvent.java         event published when an order becomes READY
│   ├── NotificationClient.java      RestClient call to the microservice (after commit)
│   └── ConceptDemoService.java      SessionFactory/Session/Transaction + IoC container demo
├── repository/                      Spring Data JPA interfaces (2 HQL queries)
├── entity/                          MenuItem, CustomerOrder, OrderItem, Payment(+Cash/Online), StaffUser
├── dto/                             validated request/response records
├── enums/                           Category, OrderStatus, PaymentMethod, PaymentStatus, StaffRole
├── exception/                       ApiException, ApiError, GlobalExceptionHandler, PageExceptionHandler
└── security/                        SecurityConfig, StaffUserDetailsService

noq-app/src/main/resources
├── application.properties           MySQL, JPA, cache, actuator, NoQ settings
├── application-h2.properties        optional in-memory profile (also used by tests)
├── data.sql                         sample menu + staff (INSERT IGNORE)
├── templates/                       index, cart, track, pay, error, fragments, staff/*
└── static/                          css/app.css, js/{app,menu,cart,track,pay,staff}.js, images/

noq-notification-service/src/main/java/com/noq/notification
├── NotificationServiceApplication.java
└── NotificationController.java      POST /api/notifications/order-ready, GET /api/notifications
```

## 3. Entity–relationship diagram

```mermaid
erDiagram
    MENU_ITEM ||--o{ ORDER_ITEM : "appears in"
    CUSTOMER_ORDER ||--|{ ORDER_ITEM : "contains"
    CUSTOMER_ORDER ||--o{ PAYMENT : "paid by (attempts)"

    MENU_ITEM {
        bigint id PK
        varchar name
        varchar description
        varchar category
        decimal price
        varchar image_url
        int available_quantity
        bit available "staff sold-out switch"
        bit active "soft delete"
        int popularity_count
        bigint version "optimistic lock"
    }
    CUSTOMER_ORDER {
        bigint id PK
        varchar order_reference UK "NQ-2026-XXXXX"
        varchar pickup_otp_hash "BCrypt, never the OTP"
        varchar status
        varchar payment_method
        varchar payment_status
        decimal subtotal
        decimal total_amount
        varchar idempotency_key UK
        datetime created_at
        datetime updated_at
        datetime completed_at
        bigint version
    }
    ORDER_ITEM {
        bigint id PK
        bigint order_id FK
        bigint menu_item_id FK
        varchar item_name_snapshot
        decimal unit_price_snapshot
        int quantity
        decimal line_total
    }
    PAYMENT {
        bigint id PK
        bigint order_id FK
        varchar payment_type "discriminator: CASH | ONLINE"
        decimal amount
        varchar status
        datetime created_at
        varchar received_by "CASH only"
        varchar transaction_reference "ONLINE only"
        varchar gateway "ONLINE only"
    }
    STAFF_USER {
        bigint id PK
        varchar username UK
        varchar password_hash
        varchar role
        bit active
    }
```

### Payment inheritance (SINGLE_TABLE)

```mermaid
classDiagram
    class Payment {
        <<abstract, @Entity>>
        Long id
        CustomerOrder customerOrder
        BigDecimal amount
        PaymentStatus status
        getPaymentType()*
    }
    class CashPayment {
        <<@DiscriminatorValue CASH>>
        String receivedBy
    }
    class OnlinePayment {
        <<@DiscriminatorValue ONLINE>>
        String transactionReference
        String gateway
    }
    Payment <|-- CashPayment
    Payment <|-- OnlinePayment
```

Both subclasses live in **one** `payment` table; Hibernate writes `payment_type = 'CASH'` or `'ONLINE'` and
uses that column to decide which Java class to instantiate when reading.

## 4. Order status life-cycle

```mermaid
stateDiagram-v2
    [*] --> PLACED : customer places order
    PLACED --> CONFIRMED : staff accepts (ONLINE orders must be PAID)
    PLACED --> CANCELLED : customer, staff, or unpaid ONLINE order after 15 min
    CONFIRMED --> PREPARING : staff
    CONFIRMED --> CANCELLED : staff
    PREPARING --> READY : staff → notification service called
    READY --> COMPLETED : staff verifies pickup OTP (order must be PAID)
    COMPLETED --> [*]
    CANCELLED --> [*] : stock restored, PAID → REFUNDED
```

Payment status is separate: `PENDING → PAID` (or `FAILED → PAID` after a retry), and `PAID → REFUNDED` when a paid
order is cancelled.

## 5. Request flow — placing an order

```mermaid
sequenceDiagram
    autonumber
    actor U as Customer (browser)
    participant JS as cart.js
    participant F as Security filters
    participant RC as OrderRestController
    participant A as LoggingAspect + @Transactional proxy
    participant OS as OrderService
    participant R as Repositories (Hibernate)
    participant DB as MySQL

    U->>JS: click "Place order"
    JS->>F: POST /api/orders {items:[{menuItemId,quantity}], paymentMethod, idempotencyKey}
    F->>RC: permitted (public endpoint)
    RC->>RC: @Valid → 400 if invalid
    RC->>A: placeOrder(request)
    A->>OS: begin transaction, start timer
    OS->>R: findByIdempotencyKey (duplicate? → 409)
    loop each item
        OS->>R: findById(menuItemId)  (DB, never the cache)
        OS->>OS: active? available? enough stock? (→ 400/409)
        OS->>OS: reduceStock(), popularity += qty, snapshot name & price
    end
    OS->>OS: totals on the server, order reference, SecureRandom OTP → BCrypt hash
    OS->>R: save(order) (cascades order items)
    OS->>OS: register "evict menu caches after commit"
    A->>DB: COMMIT — UPDATE menu_item ... WHERE version=? (optimistic lock)
    A->>A: after commit: clear menu + popularItems caches
    A-->>RC: OrderPlacedResponse (contains the plain OTP once)
    RC-->>JS: 201 Created + Location
    JS->>JS: store {ref, otp} in localStorage, clear cart
    JS-->>U: redirect to /track (counter) or /pay/{ref} (online demo)
```

## 6. Request flow — order becomes READY (microservice integration)

```mermaid
sequenceDiagram
    autonumber
    actor K as Kitchen staff
    participant RC as OrderRestController
    participant OS as OrderService
    participant DB as MySQL
    participant NC as NotificationClient
    participant NS as noq-notification-service :8081
    actor C as Customer tracking page

    K->>RC: PATCH /api/orders/{ref}/status {"status":"READY"}
    RC->>OS: updateStatus(ref, READY)
    OS->>OS: canMoveTo(READY)? else 409
    OS->>OS: publishEvent(OrderReadyEvent)
    OS->>DB: COMMIT
    Note over NC: @TransactionalEventListener(AFTER_COMMIT)<br/>runs only if the commit succeeded
    NC->>NS: POST /api/notifications/order-ready {orderReference, eventType}
    alt service up
        NS-->>NC: 201 {status: "RECEIVED"}
    else service down / timeout (2 s)
        NC->>NC: log warning — order stays READY
    end
    RC-->>K: 200 order JSON
    loop every 5 s
        C->>RC: GET /api/orders/{ref}/status
        RC-->>C: {"status":"READY"} → "Your order is ready!"
    end
```

## 7. Request flow — a Thymeleaf page (MVC)

```mermaid
sequenceDiagram
    participant B as Browser
    participant DS as DispatcherServlet
    participant HC as HomeController
    participant MS as MenuService (proxy)
    participant CC as Caffeine cache
    participant DB as MySQL
    participant TL as Thymeleaf

    B->>DS: GET /
    DS->>HC: index(Model)
    HC->>MS: getMenu(null)
    MS->>CC: key "ALL"?
    alt cache hit
        CC-->>MS: cached List<MenuItemResponse>
    else cache miss
        MS->>DB: HQL select m from MenuItem m where m.active = true
        MS->>CC: store
    end
    HC->>HC: model.addAttribute("menu", ...)
    HC-->>DS: "index"
    DS->>TL: render templates/index.html with the model
    TL-->>B: HTML
```

## 8. Security map

| Path | Who |
|---|---|
| `/`, `/cart`, `/track`, `/pay/*`, static files, `/actuator/health` | everyone |
| `GET /api/menu/**` | everyone |
| `POST /api/orders`, `GET /api/orders/{ref}/status` | everyone |
| `GET /api/orders/{ref}`, `POST .../cancel`, `POST .../payments/mock-online` | everyone, but the **service** checks the `X-Pickup-Otp` header (or staff login) |
| `GET /api/orders`, `PATCH .../status`, `POST .../verify-pickup`, `POST .../payments/counter-confirmation`, `/api/demo/**`, `/actuator/**` | STAFF (ADMIN includes STAFF) |
| `POST/PUT/PATCH/DELETE /api/menu/**`, `/staff/menu/**` | ADMIN |
| `/staff/**` | STAFF |

Unauthenticated API calls get **401** JSON, wrong role gets **403** JSON; browser pages redirect to `/staff/login`.
