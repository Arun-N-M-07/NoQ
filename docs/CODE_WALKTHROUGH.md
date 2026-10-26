# Code Walkthrough

## Part 1 — The life of one order

Follow `NQ-2026-DEBCE` from the first click to the moment the food is handed over.

| # | Where | What happens |
|---|---|---|
| 1 | `HomeController.index()` | Browser asks for `/`. The controller calls `MenuService.getMenu(null)` and `getPopularItems(6)`, puts them in the `Model` and returns `"index"`. Thymeleaf renders `templates/index.html`. |
| 2 | `MenuService.getMenu()` | `@Cacheable("menu")`: first call runs the HQL `select m from MenuItem m where m.active = true order by m.id` and stores the list of DTOs in Caffeine; later calls skip the database. |
| 3 | `static/js/menu.js` | Filter chips, search and sort run in the browser. "Add" stores `{id, name, price, qty}` in `localStorage` (`NoQ.cart` in `app.js`). |
| 4 | `static/js/cart.js` | The cart page refreshes prices/stock from `GET /api/menu`, highlights problems, and on *Place order* sends `POST /api/orders` with only ids, quantities, payment method and a random `idempotencyKey`. |
| 5 | `SecurityConfig` | `POST /api/orders` is public, so the request reaches the controller. |
| 6 | `OrderRestController.placeOrder()` | `@Valid` checks the `PlaceOrderRequest` (non-empty, quantity 1–20, payment method present). Failure → `MethodArgumentNotValidException` → `GlobalExceptionHandler` → **400**. |
| 7 | Spring proxy | The call goes through the proxy Spring created around `OrderService`: `LoggingAspect` starts a timer and `@Transactional` opens a database transaction. |
| 8 | `OrderService.placeOrder()` | Rejects a repeated `idempotencyKey` (409). Merges duplicate lines (sorted by id). For each line locks the `MenuItem` row **in the database** (`SELECT … FOR UPDATE`, never from the cache), checks active / available / stock, calls `reduceStock()` (stock −qty, popularity +qty) and creates an `OrderItem` that snapshots name and price. |
| 9 | `OrderService` | Calculates subtotal and total with `BigDecimal`, generates `NQ-2026-XXXXX` with `SecureRandom` (checked for uniqueness), generates the OTP with `PickupCodeService.newOtp()` and stores only its BCrypt hash. `orderRepository.save(order)` cascades to the order items. Menu cache eviction is registered to run after the commit. |
| 10 | Commit | Hibernate flushes: `INSERT customer_order`, `INSERT order_item` ×2, `UPDATE menu_item ... WHERE id=? AND version=?`. If another checkout changed the same item first, the version check fails → **409** "changed by another request". |
| 11 | Response | `201 Created` + `OrderPlacedResponse` with the plain OTP (the only time it is ever sent) and the server's item summary. `cart.js` keeps it in `localStorage`, clears the cart and shows the **order confirmation** (reference, items, total, payment status, pickup OTP). While open, it re-reads the order with `GET /api/orders/{ref}` and header `X-Pickup-Otp`, which `PickupCodeService.checkCustomerAccess()` verifies (5 wrong tries → locked). |
| 12 | `staff.js` (Kitchen page) → `PATCH /api/orders/{ref}/status` | New orders start as PREPARING and appear on the Kitchen page's *Incoming Orders*. The kitchen clicks *Ready for Pickup* (→ READY_FOR_PICKUP). `OrderService.updateStatus()` asks `OrderStatus.canMoveTo()`; illegal jumps → 409. |
| 13 | READY_FOR_PICKUP | `updateStatus()` publishes `OrderReadyEvent`. After the commit, `NotificationClient.onOrderReady()` (`@TransactionalEventListener(AFTER_COMMIT)`) POSTs to the microservice with a 2-second timeout. If it is down, a warning is logged and nothing else changes. |
| 14 | `PaymentService.confirmCounterPayment()` | On the Staff page (ready orders only), after a correct OTP (see 15) the page records the payment: a `CashPayment` row is added (`payment_type='CASH'`), payment status → PAID. A second click → 409. |
| 15 | `OrderService.verifyPickup()`, then `collect()` | Staff type the customer's OTP and click *Verify OTP*: must be READY_FOR_PICKUP and the OTP must match the hash (then the page records the payment, 14) → "OTP verified. Food can be delivered." (nothing saved). *Confirm handover* calls `collect()`, which checks everything again → COLLECTED, `completedAt` set; the card leaves the list. The customer's still-open confirmation (`cart.js`, `GET /api/orders/{ref}` with the OTP header every 5 s) shows READY FOR PICKUP, then "Collected — enjoy your meal!". Any later attempt → 409 "already collected". |

## Part 2 — Every important class

### Application & configuration
| Class / file | Responsibility |
|---|---|
| `NoqApplication` | Starts Spring Boot. `@SpringBootApplication` turns on component scanning + auto-configuration; `@EnableCaching` turns on `@Cacheable`. |
| `application.properties` | Database URL/credentials from env vars or `.env`, `ddl-auto=update`, Caffeine spec, actuator, NoQ settings. |
| `application-h2.properties` | In-memory database profile used by tests and quick demos. |
| `data.sql` | Sample menu and staff users, `INSERT IGNORE` so restarts don't duplicate. |

### Entities (`entity/`) — one class = one table
| Class | Notes |
|---|---|
| `MenuItem` | Price `BigDecimal`, stock, `available` (sold-out switch), `active` (false = hidden from the menu), `popularityCount`, `@Version` for optimistic locking. `isOrderable()`, `reduceStock()`, `restoreStock()` keep the stock rules in one place. |
| `CustomerOrder` | Unique `orderReference`, `pickupOtpHash`, `status`, `paymentMethod` (always PAY_AT_COUNTER), `paymentStatus`, money fields, unique `idempotencyKey`, `@CreationTimestamp`/`@UpdateTimestamp`, `@Version`. `@OneToMany` to items and payments with helper methods that set both sides. |
| `OrderItem` | `@ManyToOne` to order and to menu item, plus name/price **snapshots** so old bills never change. |
| `Payment` (abstract) | `@Inheritance(SINGLE_TABLE)` + `@DiscriminatorColumn(payment_type)`. |
| `CashPayment` | `@DiscriminatorValue("CASH")`, `receivedBy`. |
| `StaffUser` | Username, BCrypt `passwordHash`, `role`, `active`. |

### Repositories (`repository/`)
| Interface | Notes |
|---|---|
| `MenuItemRepository` | `findAllActive`, `findActiveByCategory`, **HQL 1** `findPopularAvailable(Limit)`, derived `findAllByOrderByActiveDescCategoryAscNameAsc`. |
| `CustomerOrderRepository` | `findByOrderReference`, `findByIdempotencyKey`, `existsByOrderReference`, **HQL 2** `findByStatusNewestFirst`, `findByStatusInOrderByCreatedAtDesc` (all open orders for the staff board), plus `findWithLockById` / `findWithLockByOrderReference` (`SELECT ... FOR UPDATE`). |
| `StaffUserRepository` | `findByUsername` for login. |

### Services (`service/`) — all business rules live here
| Class | Notes |
|---|---|
| `MenuService` | Menu reads with `@Cacheable`; the kitchen's availability switch calls `evictMenuCaches()`, which clears the caches only after the transaction commits (`TransactionSynchronization.afterCommit`), nothing on rollback. Returns DTOs, never entities. |
| `OrderService` | Checkout, status transitions, cancel (API), OTP check + collect, stock restoration, order reference generation, READY_FOR_PICKUP event. |
| `PaymentService` | Counter confirmation (`CashPayment`), duplicate-payment protection, no payment for cancelled orders. |
| `PickupCodeService` | 6-digit OTP from `SecureRandom`, BCrypt hash/verify, customer access check (order read-back) with a 5-attempt limit (the attempt is counted atomically *before* the BCrypt check, so parallel guesses can't bypass it). |
| `OrderReadyEvent` | Tiny record published when an order becomes READY_FOR_PICKUP. |
| `NotificationClient` | `RestClient` with timeouts, `@TransactionalEventListener(AFTER_COMMIT)`, swallows and logs failures. |
| `ConceptDemoService` | Viva demo: unwraps Spring's `SessionFactory`, opens a `Session`, runs HQL in a manual `Transaction`; inspects the `ApplicationContext`. |

### Web layer
| Class | Notes |
|---|---|
| `HomeController` | Customer pages (menu, cart); computes "open now" from configured hours. |
| `StaffController` | Login page; `/staff` sends the kitchen to the Kitchen page and counter staff to the Staff page. |
| `MenuRestController`, `OrderRestController`, `PaymentRestController`, `DemoRestController` | JSON endpoints, correct status codes (201 + `Location`, 204), `@Valid` request bodies. |
| `GlobalExceptionHandler` | `@RestControllerAdvice`: turns every exception into the `ApiError` JSON shape with the right status. |
| `ApiException` | One runtime exception that carries an `HttpStatus` (`notFound`, `badRequest`, `conflict`, `forbidden`). |

### Cross-cutting
| Class | Notes |
|---|---|
| `LoggingAspect` | `@Around("execution(public * com.noq.service..*(..))")` logs duration and failures; never logs arguments (OTPs). |
| `SecurityConfig` | URL rules per role, form login for pages, HTTP Basic for Postman, JSON 401/403 for the API, BCrypt `PasswordEncoder`. |
| `StaffUserDetailsService` | Loads the kitchen/staff login from the database; its role (KITCHEN or STAFF) becomes the Spring Security role. |

### Front end
| File | Notes |
|---|---|
| `templates/fragments.html` | Shared `<head>`, customer header (cart badge), staff header, footer. |
| `templates/index.html` | "Canteen Menu" title, filter/search/sort, menu cards. |
| `templates/cart.html`, `error.html` | Checkout (pay at counter), then the order confirmation with the OTP card and live status; friendly errors. |
| `templates/staff/*.html` | Login, Orders list, Kitchen (two tabs), menu table, add/edit form with server-side error messages. |
| `static/js/app.js` | Cart and last order confirmation in `localStorage`, `api()` fetch wrapper, toast, image fallback. |
| `static/js/menu.js`, `cart.js`, `staff.js`, `kitchen.js` | One small script per page; the Kitchen page reuses `staff.js` read-only for Incoming Orders and adds `kitchen.js` for the tabs and availability toggles. |
| `static/css/app.css` | Design tokens (warm neutral + saffron accent), responsive grid, staff Orders list. |

### Microservice
| Class | Notes |
|---|---|
| `NotificationServiceApplication` | Separate Spring Boot app on port 8081. |
| `NotificationController` | Validates `{orderReference, eventType}`, logs, stores the last 100 acknowledgements, returns 201. |

### Tests
| Test | Covers |
|---|---|
| `OrderStatusTest` | Allowed / forbidden status transitions (plain JUnit). |
| `MenuApiTest` | Menu retrieval, category filter, sold-out flag, Kitchen availability toggle (only KITCHEN may flip it; saved; customer menu shows "Not available"; direct order rejected without stock change or order row), Kitchen page has two tabs, HQL popular query, 401/403/201 authorization with real BCrypt login, validation errors, cache fill + eviction, eviction only after commit (not on rollback), soft delete. |
| `OrderApiTest` | Order creation, server-side totals, stock & popularity, invalid orders (400/409), duplicate submission, full counter flow incl. payment and pickup, only counter payment exists (online endpoint closed), cancellation restores stock, customer reads own order only with the OTP (never sees it), persisted COMPLETED visible to the customer, staff page is one list, OTP lockout on the customer read, tracking/payment pages removed, staff authorization, **concurrent last-item race**, Hibernate & Spring demos. Also: repeated cancellation, cancelled orders can't be paid/prepared/collected, double "Mark paid", cancel-vs-payment race, 20 parallel OTP guesses. |
| `NotificationControllerTest` | Microservice accepts valid events, rejects invalid ones. |
