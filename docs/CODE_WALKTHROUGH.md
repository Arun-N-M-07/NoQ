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
| 8 | `OrderService.placeOrder()` | Rejects a repeated `idempotencyKey` (409). Merges duplicate lines. For each line loads the `MenuItem` **from the database** (never from the cache), checks active / available / stock, calls `reduceStock()` (stock −qty, popularity +qty) and creates an `OrderItem` that snapshots name and price. |
| 9 | `OrderService` | Calculates subtotal and total with `BigDecimal`, generates `NQ-2026-XXXXX` with `SecureRandom` (checked for uniqueness), generates the OTP with `PickupCodeService.newOtp()` and stores only its BCrypt hash. `orderRepository.save(order)` cascades to the order items. Menu cache eviction is registered to run after the commit. |
| 10 | Commit | Hibernate flushes: `INSERT customer_order`, `INSERT order_item` ×2, `UPDATE menu_item ... WHERE id=? AND version=?`. If another checkout changed the same item first, the version check fails → **409** "changed by another request". |
| 11 | Response | `201 Created` + `OrderPlacedResponse` with the plain OTP (the only time it is ever sent). `cart.js` saves `{ref, otp}` in `localStorage`, clears the cart and opens `/track?ref=…` (or `/pay/{ref}` for the online demo). |
| 12 | `track.js` | Every 5 s `GET /api/orders/{ref}/status` (public, tiny). When the status changes it loads full details with header `X-Pickup-Otp`, which `PickupCodeService.checkCustomerAccess()` verifies (5 wrong tries → locked). |
| 13 | `staff.js` → `PATCH /api/orders/{ref}/status` | Kitchen clicks *Confirm*, *Start preparing*, *Mark ready*. `OrderService.updateStatus()` asks `OrderStatus.canMoveTo()`; illegal jumps → 409. |
| 14 | READY | `updateStatus()` publishes `OrderReadyEvent`. After the commit, `NotificationClient.onOrderReady()` (`@TransactionalEventListener(AFTER_COMMIT)`) POSTs to the microservice with a 2-second timeout. If it is down, a warning is logged and nothing else changes. |
| 15 | Customer page | Next poll sees READY → green "Your order is ready!" banner. |
| 16 | `PaymentService.confirmCounterPayment()` | Staff clicks *Mark paid*: a `CashPayment` row is added (`payment_type='CASH'`), payment status → PAID. A second click → 409. |
| 17 | `OrderService.verifyPickup()` | Staff types the OTP. Must be READY, PAID, OTP matches the hash → status COMPLETED, `completedAt` set. Any later attempt → 409 "already collected". |

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
| `MenuItem` | Price `BigDecimal`, stock, `available` (sold-out switch), `active` (soft delete), `popularityCount`, `@Version` for optimistic locking. `isOrderable()`, `reduceStock()`, `restoreStock()` keep the stock rules in one place. |
| `CustomerOrder` | Unique `orderReference`, `pickupOtpHash`, `status`, `paymentMethod`, `paymentStatus`, money fields, unique `idempotencyKey`, `@CreationTimestamp`/`@UpdateTimestamp`, `@Version`. `@OneToMany` to items and payments with helper methods that set both sides. |
| `OrderItem` | `@ManyToOne` to order and to menu item, plus name/price **snapshots** so old bills never change. |
| `Payment` (abstract) | `@Inheritance(SINGLE_TABLE)` + `@DiscriminatorColumn(payment_type)`. |
| `CashPayment` | `@DiscriminatorValue("CASH")`, `receivedBy`. |
| `OnlinePayment` | `@DiscriminatorValue("ONLINE")`, `transactionReference`, `gateway`. |
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
| `MenuService` | Menu reads with `@Cacheable`; every write calls `evictMenuCaches()`, which clears the caches only after the transaction commits (`TransactionSynchronization.afterCommit`), nothing on rollback; validation of stock edits, soft delete. Returns DTOs, never entities. |
| `OrderService` | Checkout, status transitions, customer cancel, pickup verification, stock restoration, order reference generation, READY event, and `expireUnpaidOnlineOrders()` (`@Scheduled` every minute: cancels unpaid ONLINE orders older than 15 min through the same `cancel()` rules). |
| `PaymentService` | Counter confirmation (`CashPayment`) and simulated online payment (`OnlinePayment`), duplicate-payment protection, refuses online payments after the 15-minute window. |
| `PickupCodeService` | 6-digit OTP from `SecureRandom`, BCrypt hash/verify, customer access check with a 5-attempt limit (the attempt is counted atomically *before* the BCrypt check, so parallel guesses can't bypass it). |
| `PaymentGateway` / `MockPaymentGateway` | Interface + simulated implementation; a real gateway would be a second implementation. |
| `OrderReadyEvent` | Tiny record published when an order becomes READY. |
| `NotificationClient` | `RestClient` with timeouts, `@TransactionalEventListener(AFTER_COMMIT)`, swallows and logs failures. |
| `ConceptDemoService` | Viva demo: unwraps Spring's `SessionFactory`, opens a `Session`, runs HQL in a manual `Transaction`; inspects the `ApplicationContext`. |

### Web layer
| Class | Notes |
|---|---|
| `HomeController` | Customer pages; computes "open now" from configured hours. |
| `StaffController` | Login page and dashboard shell. |
| `StaffMenuController` | Classic form handling: `@Valid @ModelAttribute` + `BindingResult`, Post/Redirect/Get, flash messages. |
| `MenuRestController`, `OrderRestController`, `PaymentRestController`, `DemoRestController` | JSON endpoints, correct status codes (201 + `Location`, 204), `@Valid` request bodies. |
| `GlobalExceptionHandler` | `@RestControllerAdvice`: turns every exception into the `ApiError` JSON shape with the right status. |
| `PageExceptionHandler` | Same idea for HTML pages → `error.html`. |
| `ApiException` | One runtime exception that carries an `HttpStatus` (`notFound`, `badRequest`, `conflict`, `forbidden`). |

### Cross-cutting
| Class | Notes |
|---|---|
| `LoggingAspect` | `@Around("execution(public * com.noq.service..*(..))")` logs duration and failures; never logs arguments (OTPs). |
| `SecurityConfig` | URL rules per role, form login for pages, HTTP Basic for Postman, JSON 401/403 for the API, BCrypt `PasswordEncoder`. |
| `StaffUserDetailsService` | Loads staff from the database; ADMIN gets both ADMIN and STAFF roles. |

### Front end
| File | Notes |
|---|---|
| `templates/fragments.html` | Shared `<head>`, customer header (cart badge), staff header, footer. |
| `templates/index.html` | Hero, live open/closed status, track box, popular strip, filter/search/sort, menu cards. |
| `templates/cart.html`, `track.html`, `pay.html`, `error.html` | Checkout, live tracking with progress bar and OTP card, demo payment, friendly errors. |
| `templates/staff/*.html` | Login, order board, menu table, add/edit form with server-side error messages. |
| `static/js/app.js` | Cart and recent orders in `localStorage`, `api()` fetch wrapper, toast, image fallback. |
| `static/js/menu.js`, `cart.js`, `track.js`, `pay.js`, `staff.js` | One small script per page. |
| `static/css/app.css` | Design tokens (warm neutral + saffron accent), responsive grid, staff board. |

### Microservice
| Class | Notes |
|---|---|
| `NotificationServiceApplication` | Separate Spring Boot app on port 8081. |
| `NotificationController` | Validates `{orderReference, eventType}`, logs, stores the last 100 acknowledgements, returns 201. |

### Tests
| Test | Covers |
|---|---|
| `OrderStatusTest` | Allowed / forbidden status transitions (plain JUnit). |
| `MenuApiTest` | Menu retrieval, category filter, sold-out flag, HQL popular query, 401/403/201 authorization with real BCrypt login, validation errors, cache fill + eviction, eviction only after commit (not on rollback), soft delete. |
| `OrderApiTest` | Order creation, server-side totals, stock & popularity, invalid orders (400/409), duplicate submission, full counter flow incl. payment and pickup, online mock payment fail/success, cancellation restores stock, OTP privacy & lockout, staff authorization, **concurrent last-item race**, Hibernate & Spring demos. Also: repeated cancellation, cancelled orders can't be paid/prepared/collected, unpaid-online-order expiry (once, stock restored, paid/counter orders untouched), late payment refused, payment-vs-expiry race, double "Mark paid", double-click payment, cancel-vs-payment race, 20 parallel OTP guesses. |
| `NotificationControllerTest` | Microservice accepts valid events, rejects invalid ones. |
