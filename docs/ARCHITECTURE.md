# NoQ — Architecture

Static PNG versions of the main diagrams (runtime architecture, request flow, workflows, order life-cycle, ER diagram, notification sequence) are in [`diagrams/`](diagrams/). They are the same figures used in the project report.

## 1. System overview

```mermaid
flowchart LR
    subgraph Browser
        C["Customer pages<br/>menu · cart + order confirmation<br/>(Thymeleaf HTML + vanilla JS)"]
        S["Kitchen page (availability, incoming orders)<br/>Staff page (ready orders, pickup)"]
        P["Postman"]
    end

    subgraph APP["noq-app  (Spring Boot, port 8080)"]
        direction TB
        SEC["Spring Security filter chain<br/>form login · HTTP Basic · roles"]
        MVC["@Controller<br/>HomeController · StaffController"]
        REST["@RestController<br/>Menu · Order · Demo"]
        ADV["@RestControllerAdvice<br/>GlobalExceptionHandler"]
        SVC["@Service (@Transactional)<br/>MenuService · OrderService<br/>PickupCodeService · NotificationClient"]
        AOP["LoggingAspect (@Around)"]
        CACHE[("Caffeine cache<br/>menu · popularItems")]
        REPO["Spring Data JPA repositories<br/>+ HQL @Query"]
        HIB["Hibernate ORM<br/>(SessionFactory / Session)"]
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
    SVC -- "RestClient POST (after commit)" --> NS
```

**Monolith + one microservice.** The customer site, the Kitchen and Staff pages and the REST API are one deployable
Spring Boot application (a *modular monolith*). Notification sending is split out into a separate service with
its own process, port and build, talking to the main app only through HTTP/JSON.

## 2. Directory structure

```
noq-app/src/main/java/com/noq
├── NoqApplication.java              @SpringBootApplication + @EnableCaching
├── aspect/LoggingAspect.java        AOP: timing + failure logging for every service method
├── controller/                      MVC page controllers (return template names)
│   ├── HomeController.java          /  /cart
│   └── StaffController.java         /staff/login  /staff  /staff/kitchen
├── restcontroller/                  JSON API
│   ├── MenuRestController.java      /api/menu/**
│   ├── OrderRestController.java     /api/orders/**
│   └── DemoRestController.java      /api/demo/** (viva demos)
├── service/
│   ├── MenuService.java             menu reads, Kitchen availability switch, @Cacheable, cache eviction after commit
│   ├── OrderService.java            checkout, status transitions, cancel (API), one-step pickup
│   ├── PickupCodeService.java       SecureRandom OTP, BCrypt hash, attempt limiter
│   ├── OrderReadyEvent.java         event published when an order becomes READY_FOR_PICKUP
│   ├── NotificationClient.java      RestClient call to the microservice (after commit)
│   └── ConceptDemoService.java      SessionFactory/Session/Transaction + IoC container demo
├── repository/                      Spring Data JPA interfaces (2 HQL queries)
├── entity/                          MenuItem, CustomerOrder, OrderItem, StaffUser
├── dto/                             validated request/response records
├── enums/                           Category, OrderStatus, StaffRole
├── exception/                       ApiException, ApiError, GlobalExceptionHandler
└── security/                        SecurityConfig, StaffUserDetailsService

noq-app/src/main/resources
├── application.properties           MySQL, JPA, cache, actuator, NoQ settings
├── application-h2.properties        optional in-memory profile (also used by tests)
├── data.sql                         sample menu + staff (INSERT IGNORE)
├── templates/                       index, cart, error, fragments, staff/*
└── static/                          css/app.css, js/{app,menu,cart,staff,kitchen}.js, images/

noq-notification-service/src/main/java/com/noq/notification
├── NotificationServiceApplication.java
└── NotificationController.java      POST /api/notifications/order-ready, GET /api/notifications
```

## 3. Entity–relationship diagram

```mermaid
erDiagram
    MENU_ITEM ||--o{ ORDER_ITEM : "appears in"
    CUSTOMER_ORDER ||--|{ ORDER_ITEM : "contains"

    MENU_ITEM {
        bigint id PK
        varchar name
        varchar description
        varchar category
        decimal price
        varchar image_url
        bit available "Kitchen Available / Not available switch"
        bit active "false = hidden from the menu"
        int popularity_count
        bigint version "optimistic lock"
    }
    CUSTOMER_ORDER {
        bigint id PK
        varchar order_reference UK "NQ-2026-XXXXX"
        varchar pickup_otp_hash "BCrypt, never the OTP"
        varchar status
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
    STAFF_USER {
        bigint id PK
        varchar username UK
        varchar password_hash
        varchar role
        bit active
    }
```

## 4. Order status life-cycle

```mermaid
stateDiagram-v2
    [*] --> PREPARING : customer places order
    PREPARING --> READY_FOR_PICKUP : Kitchen "Ready for Pickup" → notification service called
    PREPARING --> CANCELLED : API only
    READY_FOR_PICKUP --> COLLECTED : Staff "Verify & Complete Pickup": one call checks the order is ready and the OTP is right
    COLLECTED --> [*]
    CANCELLED --> [*] : popularity reversed
```

The Kitchen page lists PREPARING orders; the Staff page lists READY_FOR_PICKUP orders.
NoQ does not track payment: the customer pays by hand at the counter, and the order's status alone drives the workflow.

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
    JS->>F: POST /api/orders {items:[{menuItemId,quantity}], idempotencyKey}
    F->>RC: permitted (public endpoint)
    RC->>RC: @Valid → 400 if invalid
    RC->>A: placeOrder(request)
    A->>OS: begin transaction, start timer
    OS->>R: findByIdempotencyKey (duplicate? → 409)
    loop each item
        OS->>R: findById(menuItemId)  (DB, never the cache)
        OS->>OS: active? available? (→ 400/409)  no stock check
        OS->>R: adjustPopularity(id, +qty)  one atomic UPDATE, no lock
        OS->>OS: snapshot name & price
    end
    OS->>OS: totals on the server, order reference, SecureRandom OTP → BCrypt hash
    OS->>R: save(order) (cascades order items)
    OS->>OS: register "evict menu caches after commit"
    A->>DB: COMMIT
    A->>A: after commit: clear menu + popularItems caches
    A-->>RC: OrderPlacedResponse (contains the plain OTP once)
    RC-->>JS: 201 Created + Location
    JS->>JS: keep the response in localStorage (so a refresh keeps the OTP), clear cart
    JS-->>U: show the order confirmation: reference, items, total, pickup OTP
```

## 6. Request flow — order becomes READY_FOR_PICKUP (microservice integration)

```mermaid
sequenceDiagram
    autonumber
    actor K as Kitchen (Kitchen page)
    participant RC as OrderRestController
    participant OS as OrderService
    participant DB as MySQL
    participant NC as NotificationClient
    participant NS as noq-notification-service :8081

    K->>RC: PATCH /api/orders/{ref}/status {"status":"READY_FOR_PICKUP"}
    RC->>OS: updateStatus(ref, READY_FOR_PICKUP)
    OS->>OS: canMoveTo(READY_FOR_PICKUP)? else 409
    OS->>OS: publishEvent(OrderReadyEvent)
    OS->>DB: COMMIT
    Note over NC: @TransactionalEventListener(AFTER_COMMIT)<br/>runs only if the commit succeeded
    NC->>NS: POST /api/notifications/order-ready {orderReference, eventType}
    alt service up
        NS-->>NC: 201 {status: "RECEIVED"}
    else service down / timeout (2 s)
        NC->>NC: log warning — order stays READY_FOR_PICKUP
    end
    RC-->>K: 200 order JSON (the order moves from the Kitchen list to the Staff page)
    actor C as Customer confirmation (cart.js)
    loop every 5 s while open, until COLLECTED/CANCELLED
        C->>RC: GET /api/orders/{ref} + X-Pickup-Otp
        RC-->>C: {"status":"READY_FOR_PICKUP", ...} (persisted state)
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
| `/`, `/cart`, static files, `/actuator/health` | everyone |
| `GET /api/menu/**` | everyone |
| `POST /api/orders` | everyone |
| `GET /api/orders/{ref}` | everyone, but the **service** checks the `X-Pickup-Otp` header (or staff login) |
| `/staff/kitchen`, `PATCH /api/menu/{id}/availability`, `PATCH /api/orders/{ref}/status` | KITCHEN |
| `GET /api/orders` | KITCHEN or STAFF |
| `POST .../pickup`, `/api/demo/**`, `/actuator/**` | STAFF |
| `/staff` | KITCHEN or STAFF (redirects the kitchen to `/staff/kitchen`) |

Unauthenticated API calls get **401** JSON, wrong role gets **403** JSON; browser pages redirect to `/staff/login`.
