# Requirement → Implementation Map

Paths are relative to `noq-app/src/main/java/com/noq/` unless they start with a module or folder name.
"Test" names refer to methods in `noq-app/src/test/java/com/noq/`.

## Mandatory stack

| Requirement | Where |
|---|---|
| Java 21, Spring Boot 3.x, Maven | `noq-app/pom.xml`, `noq-notification-service/pom.xml` (`java.version=21`, Boot 3.5.16), `mvnw` |
| Spring MVC + Thymeleaf | `controller/*`, `noq-app/src/main/resources/templates/` |
| Spring Data JPA + Hibernate + MySQL 8 | `repository/*`, `entity/*`, `application.properties` (`mysql-connector-j`) |
| HTML5, CSS3, vanilla JS | `static/css/app.css`, `static/js/*.js` |
| Spring Validation | `dto/*Request` annotations, `@Valid` in controllers |
| Actuator / health | `/actuator/health` (both apps), `management.*` in properties |
| Caffeine cache | `spring.cache.*` properties, `MenuService` `@Cacheable` + `evictMenuCaches()` (after commit) |
| Spring AOP | `aspect/LoggingAspect.java` |
| Spring Boot Test + JUnit | `noq-app/src/test/...`, `noq-notification-service/src/test/...` |
| Postman | `postman/NoQ.postman_collection.json` |
| Git | repository root, `.gitignore`, README §8 commit sequence |

## Customer features (§3A)

| Requirement | Where |
|---|---|
| Branding + tagline "No Queue. Just Order." | `templates/fragments.html` (header), `index.html` hero |
| Canteen operating status | `HomeController.index()` (`noq.canteen.opens-at/closes-at`), hero pill |
| Categories, filter, search, sort | `index.html` chips/search/sort, `static/js/menu.js`; API `GET /api/menu?category=` |
| Menu cards with image, price, description, availability | `index.html` menu grid; emoji fallback in `app.js` |
| Popular / frequently ordered items | `index.html` popular strip, `MenuService.getPopularItems()`, `GET /api/menu/popular` |
| Cart icon with count | `fragments.html` `[data-cart-count]`, `app.js updateCartBadges()` |
| Order tracking section | hero "Track an order" box + recent orders, `/track` |
| Browse without account | `SecurityConfig` permits customer pages and APIs |
| Add / increase / decrease / remove | `menu.js` stepper, `cart.js` |
| Subtotal & payable amount | `cart.html` summary |
| Choose payment method, place order | `cart.html` radio cards, `cart.js` → `POST /api/orders` |
| Confirmation with order ID + OTP | `OrderPlacedResponse`, `track.html` OTP card |
| Track status, ready message | `track.html`, `track.js` (5-second polling) |
| Cart in browser storage, validated on server | `app.js` (`localStorage`), `OrderService.placeOrder()` |

## Dynamic menu & availability (§3B)

| Requirement | Where | Test |
|---|---|---|
| All MenuItem fields incl. version | `entity/MenuItem.java` | |
| Unavailable/inactive not orderable, sold-out label | `MenuItem.isOrderable()`, `index.html` "Sold out" | `soldOutItemIsNotOrderable`, `invalidOrdersAreRejected` |
| Decrease stock on order, block over-ordering | `OrderService.placeOrder()`, `MenuItem.reduceStock()` | `placingAnOrderDeductsStock…` |
| Staff update stock/availability | `StaffMenuController`, `PATCH /api/menu/{id}/availability` | `cacheIsFilledOnReadAndEvictedOnStaffUpdate` |
| Popularity increases with orders; popular section sorted; fallback | `reduceStock()`, HQL 1, `HomeController` "House favourites" fallback | `hqlPopularQueryReturns…` |
| Cacheable menu, eviction on change (after commit, not on rollback) | `MenuService` | `cacheIsFilledOnReadAndEvictedOnStaffUpdate`, `cacheIsEvictedOnlyAfterCommitAndNotOnRollback` |
| Cache never used for checkout | `OrderService.placeOrder()` uses `menuItemRepository.findById` | |
| Abandoned online orders don't hold stock | `OrderService.expireUnpaidOnlineOrders()` (`@Scheduled`), `noq.orders.online-payment-timeout` | `abandonedOnlineOrderExpiresOnceAndReturnsStock`, `latePaymentIsRefusedEvenBeforeTheExpiryJobRuns`, `paymentRacingExpiryLeavesOneConsistentOutcome`, `twoStaffMarkingTheSameCounterOrderPaidCreateOnePayment`, `doubleClickOnSimulatedPaymentCreatesOneSuccessfulPayment`, `cancelRacingCounterPaymentNeverLeavesAnInconsistentOrder` |
| Concurrent orders safe | `@Version` on `MenuItem` and `CustomerOrder`, 409 handler | `concurrentCustomersCannotOversellTheLastItem` |

## Cart & checkout (§3C)

| Requirement | Where |
|---|---|
| Totals calculated on backend only | `OrderService.placeOrder()`; test sends a fake `"total":1` and checks 60.00 |
| Price snapshot per order item | `OrderItem` constructor (`itemNameSnapshot`, `unitPriceSnapshot`) |
| Transactional order + items + stock | `@Transactional` on `placeOrder`, cascade `ALL` |
| Unique reference + OTP | `OrderService.newOrderReference()`, `PickupCodeService.newOtp()` |

## Payments (§3D)

| Requirement | Where | Test |
|---|---|---|
| PAY_AT_COUNTER flow, staff marks paid | `PaymentService.confirmCounterPayment()`, dashboard "Mark paid" | `counterOrderFullLifecycle…` |
| Configurable workflow | `noq.orders.require-payment-before-confirm` in `OrderService.updateStatus()` | |
| Simulated online payment, success/failure | `pay.html`, `pay.js`, `PaymentService.payOnlineMock()`, `MockPaymentGateway` | `onlineOrderNeedsSuccessfulMockPayment…` |
| Payment status separate from food status | `CustomerOrder.paymentStatus` vs `status` | |
| No sensitive payment data, clearly labelled mock | `pay.html` banner, no inputs | |
| Gateway can be swapped later | `PaymentGateway` interface | |

## Order ID & OTP (§3E)

| Requirement | Where | Test |
|---|---|---|
| NQ-YYYY-XXXXX, DB-unique | `CustomerOrder.orderReference` `unique = true`, `newOrderReference()` | regex check in `placingAnOrder…` |
| Secure, independent OTP | `SecureRandom` in `PickupCodeService` | |
| OTP hashed, not exposed, not logged | `pickupOtpHash` (BCrypt), DTOs have no OTP, `LoggingAspect` logs no args | `customerNeedsOtpAndNeverSeesIt` |
| OTP only to the customer | returned once in 201 response, stored in that browser's `localStorage` | |
| Staff verify, collect only once | `OrderService.verifyPickup()` | `counterOrderFullLifecycle…` |
| Tracking with reference + OTP, 5-attempt limit | `GET /api/orders/{ref}` with `X-Pickup-Otp`, `track.html` lookup form, `PickupCodeService` | `tooManyWrongOtpsLockPublicAccess`, `parallelOtpGuessesCannotBypassTheAttemptLimit` |

## Tracking & statuses (§3F)

| Requirement | Where |
|---|---|
| Six statuses | `enums/OrderStatus.java` |
| Valid transitions only | `OrderStatus.canMoveTo()` + `OrderService.updateStatus()` (tests `OrderStatusTest` incl. `completeTransitionMatrixIsExactlyTheDocumentedOne`, which checks all 36 pairs) |
| Progress indicator, auto refresh, ready message | `track.html` `.progress`, `track.js` `setInterval(poll, 5000)` |
| Cancellation rules + stock restore (exactly once) | `OrderService.cancel()` / `cancelByCustomer()` / expiry (tests `cancellingRestoresStock`, `abandonedOnlineOrderExpiresOnceAndReturnsStock`) |

## Staff dashboard (§4)

| Requirement | Where |
|---|---|
| Separate login, server-side authorization | `SecurityConfig`, `staff/login.html`, `StaffUserDetailsService` |
| New / awaiting payment / confirmed / preparing / ready / completed | `staff/dashboard.html` filter chips, `staff.js` columns |
| Details, quantities, payment method/status, reference | order cards in `staff.js` |
| Confirm, prepare, ready, verify OTP, complete, mark paid, cancel | `staff.js` actions → REST endpoints |
| Refresh without reload | `staff.js` `setInterval(load, 10000)` + *Refresh now* |
| Add / edit / stock / sold out / reactivate / deactivate | `staff/menu.html`, `staff/menu-form.html`, `StaffMenuController` |
| Server-side validation | `MenuItemRequest` annotations + `BindingResult`; `MenuService.updateAvailability()` range check |

## Architecture (§5) and database (§6)

| Requirement | Where |
|---|---|
| Layered packages | `controller`, `restcontroller`, `service`, `repository`, `entity`, `dto`, `exception`, `aspect`, `security`, `enums` (configuration is in `application.properties`, so no `config` package was needed) |
| Constructor injection everywhere | every controller/service constructor |
| DTOs instead of entities in JSON | `dto/*Response.from(entity)` |
| Normalized schema, PK/FK/unique/index | `docs/schema-mysql.sql` (dumped from MySQL 8.4.9 after Hibernate created it) |
| `GenerationType.IDENTITY` | all entities |
| `BigDecimal` money, `LocalDateTime` timestamps | entities |
| Sample data | `data.sql` |

## Unit IV concepts (§7)

| Concept | Where to show it |
|---|---|
| A. Spring Boot & MVC, request lifecycle | `HomeController` → `MenuService` → repository → `index.html`; diagram in `ARCHITECTURE.md §7` |
| B. JPA & Hibernate, CRUD | `MenuService` create/read/update/soft-delete, `OrderService` |
| C. HQL ×2 | `MenuItemRepository.findPopularAvailable` (popular), `CustomerOrderRepository.findByStatusNewestFirst` (status filter); also 3 HQL queries in `ConceptDemoService` |
| D. Inheritance mapping | `Payment` / `CashPayment` / `OnlinePayment` (SINGLE_TABLE), `GET /api/demo/hibernate-session` shows `type(p)` grouping |
| E. SessionFactory, Session, Transaction | `ConceptDemoService.hibernateSessionDemo()`, `GET /api/demo/hibernate-session`; `@Transactional` everywhere else |
| F. Dependency injection, BeanFactory vs ApplicationContext | constructors; `GET /api/demo/spring-container` |
| G. AOP | `LoggingAspect` (console lines `OrderService.placeOrder took 85 ms`) |
| H. Caching | `MenuService`, "Cache MISS" log lines, `/actuator/metrics/cache.gets?tag=cache:menu&tag=result:hit` |

## Unit V concepts (§8–9)

| Concept | Where |
|---|---|
| REST under `/api`, GET/POST/PUT/PATCH/DELETE | `restcontroller/*`, `docs/API.md` |
| Status codes 200/201/204/400/401/403/404/409 | `GlobalExceptionHandler`, `SecurityConfig` JSON handlers; all exercised in Postman |
| `@Valid`, structured errors | DTOs, `ApiError` |
| Web services theory, REST vs SOAP | `docs/REPORT.md §7`, `docs/VIVA_GUIDE.md` |
| Separate microservice on 8081 | `noq-notification-service/` |
| RestClient integration, graceful failure | `NotificationClient` (test: `counterOrderFullLifecycle…` runs with the service unreachable) |
| Monolith vs microservices, Spring Cloud | `docs/REPORT.md §8`, `docs/VIVA_GUIDE.md` |

## DevOps, testing, deliverables (§10, §13, §14)

| Deliverable | Where |
|---|---|
| 1–3 Source + Maven | `noq-app/`, `noq-notification-service/`, `pom.xml` files |
| 4 Schema + sample records | `docs/schema-mysql.sql`, `data.sql` |
| 5 Config template | `noq-app/.env.example` |
| 6 Postman | `postman/NoQ.postman_collection.json` (47 requests, auto-tests), `docs/API.md` |
| 7 README | `README.md` |
| 8 Mermaid architecture diagram | `docs/ARCHITECTURE.md` |
| 9 Project report | `docs/REPORT.md` |
| 10 Screenshot checklist | below |
| 11 This table | `docs/REQUIREMENTS_MAP.md` |
| 12 Viva guide | `docs/VIVA_GUIDE.md` |
| 13 Class explanations + order flow | `docs/CODE_WALKTHROUGH.md` |
| Automated tests | 32 tests: `OrderStatusTest`, `MenuApiTest`, `OrderApiTest`, `NotificationControllerTest` |

---

## Screenshots

Real screenshots of the running app (headless Chrome, MySQL-backed, demo data) are saved in [`docs/screenshots/`](screenshots/):

| File | Shows |
|---|---|
| `01-customer-menu-desktop.png`, `07-customer-menu-mobile.png` | Menu home: tagline, open/closed pill, track box, popular strip, sold-out card |
| `02-menu-filter-sort-desktop.png` | Category filter + sort by price |
| `03-cart-desktop.png`, `08-cart-mobile.png` | Cart and payment options |
| `04-demo-online-payment.png` | Simulated payment page with the "demo" banner |
| `05-tracking-placed-with-pickup-code.png` | Tracking page, progress bar, pickup code |
| `06-tracking-ready.png`, `09-tracking-ready-mobile.png` | "Your order is ready!" |
| `10-staff-login.png` | Staff login |
| `11-staff-order-board.png`, `15-staff-order-board-mobile.png` | Order board with orders in every column |
| `12-staff-awaiting-counter-payment.png` | "Awaiting counter payment" filter |
| `13-admin-menu-management.png` | Menu management table |
| `14-admin-menu-form-validation-errors.png` | Server-side validation messages on the add-item form |
| `16-admin-menu-inactive-and-sold-out-rows.png` | Menu table with inactive and sold-out items after the audit run |
| `17-tracking-cancelled.png` | Customer cancels from the tracking page; stock restored |
| `18-menu-external-images-blocked-emoji-fallback.png` | All photos blocked: every card shows an emoji tile (mobile) |
| `19-admin-menu-mobile-390.png`, `20-demo-payment-mobile-390.png` | Admin menu and demo payment at 390 px |
| `21-tracking-expired-unpaid-online-order.png` | Unpaid online order cancelled automatically by the scheduler |

Not captured as files (do these yourself for the report): Postman responses (items 17–20), console log lines (22–24, 24b),
`./mvnw test` output (25), MySQL Workbench (26) and the GitHub page (27).

## Screenshot checklist (original list)

Capture these from the running application (desktop width ~1280 px unless noted) and paste them into the report.

**Customer**
- [ ] 1. Menu home: hero with tagline, open/closed pill, track box, popular strip
- [ ] 2. Menu filtered by a category + a search term, sorted by price
- [ ] 3. A sold-out card ("Margherita Pizza") and a low-stock "Only N left" label
- [ ] 4. Mobile view (375 px) of the menu with the floating "View cart" bar
- [ ] 5. Cart page with quantity steppers, summary and the two payment options
- [ ] 6. Error on checkout (e.g. set an item's stock to 0 as admin, then try to order it)
- [ ] 7. Demo payment page with the "simulation" banner; failed attempt message
- [ ] 8. Tracking page right after ordering: progress bar at "Placed", pickup code card
- [ ] 9. Tracking page showing the green "Your order is ready!" banner
- [ ] 10. Tracking page after pickup ("Collected")

**Staff**
- [ ] 11. Staff login page
- [ ] 12. Order board with orders in several columns
- [ ] 13. "Awaiting counter payment" filter
- [ ] 14. Wrong OTP toast, then successful "handed over" toast
- [ ] 15. Menu management table (on sale / sold out / inactive rows)
- [ ] 16. Add-item form showing validation errors

**APIs & services**
- [ ] 17. Postman: `POST /api/orders` 201 response with reference + OTP
- [ ] 18. Postman: a 409 (sold out) and a 400 (validation) response
- [ ] 19. Postman: 401 without login and 403 as `kitchen` on `POST /api/menu`
- [ ] 20. Postman collection runner — all requests passing
- [ ] 21. `GET /api/demo/hibernate-session` and `GET /api/demo/spring-container` JSON
- [ ] 22. Console: `LoggingAspect` timing lines and "Cache MISS" appearing only once
- [ ] 23. Two terminals: noq-app on 8080 and notification service on 8081, with the `NOTIFY customer` log line
- [ ] 24. Notification service stopped → warning in noq-app log, order still READY
- [ ] 24b. Unpaid online order cancelled automatically after the payment window (tracking page shows CANCELLED; log line "Cancelled 1 unpaid online order(s)")
- [ ] 25. `./mvnw test` → BUILD SUCCESS
- [ ] 26. MySQL Workbench / `SHOW TABLES` and `SELECT * FROM payment` showing the `payment_type` discriminator
- [ ] 27. GitHub repository page with commit history
