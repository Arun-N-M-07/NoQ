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
| Branding + tagline "No Queue. Just Order." | `templates/fragments.html` (header, under the NoQ logo) |
| Canteen operating status | **Removed by design** in the simplified version: the menu page no longer shows opening hours (pre-orders were never blocked) |
| Categories, filter, search, sort | `index.html` chips/search/sort, `static/js/menu.js`; API `GET /api/menu?category=` |
| Menu cards with image, price, description, availability | `index.html` menu grid; emoji fallback in `app.js` |
| Popular / frequently ordered items | "Most popular" sort on `index.html`, `MenuService.getPopularItems()`, `GET /api/menu/popular` |
| Cart icon with count | `fragments.html` `[data-cart-count]`, `app.js updateCartBadges()` |
| Order tracking section | **Removed by design** in the simplified version: no tracking page; the confirmation (re-opened from the cart icon) holds everything needed for pickup |
| Browse without account | `SecurityConfig` permits customer pages and APIs |
| Add / increase / decrease / remove | `menu.js` stepper, `cart.js` |
| Subtotal & payable amount | `cart.html` summary |
| Choose payment method, place order | `cart.html` radio cards, `cart.js` → `POST /api/orders` |
| Confirmation with order ID + OTP | `OrderPlacedResponse` (reference, OTP, items, total, payment status), `cart.html` confirmation section, `cart.js showConfirmation()` |
| Track status, ready message | No separate page: the open confirmation re-reads `GET /api/orders/{ref}` (OTP header) every 5 s and shows PREPARING → READY FOR PICKUP → "Collected — enjoy your meal!" (`cart.js refreshStatus()`); the ready event also goes to the notification service (logged only, not a push notification) |
| Cart in browser storage, validated on server | `app.js` (`localStorage`), `OrderService.placeOrder()` |

## Dynamic menu & availability (§3B)

| Requirement | Where | Test |
|---|---|---|
| All MenuItem fields incl. version | `entity/MenuItem.java` | |
| Unavailable/inactive not orderable, greyed out with a label | `MenuItem.isOrderable()`, `index.html` "Not available" / "Sold out", `OrderService.addLineToOrder()` 409 | `soldOutItemIsNotOrderable`, `invalidOrdersAreRejected` |
| Decrease stock on order, block over-ordering | `OrderService.placeOrder()`, `MenuItem.reduceStock()` | `placingAnOrderDeductsStock…` |
| Kitchen updates availability | Kitchen page toggle (`kitchen.js`) → `PATCH /api/menu/{id}/availability` (KITCHEN only); stock changes only with orders | `kitchenStaffToggleAvailabilityIsSavedAndEnforcedByCheckout`, `cacheIsFilledOnReadAndEvictedOnStaffUpdate` |
| Popularity increases with orders; popular items sorted by popularity | `reduceStock()`, HQL 1 (`findPopularAvailable`), "Most popular" sort | `hqlPopularQueryReturns…` |
| Cacheable menu, eviction on change (after commit, not on rollback) | `MenuService` | `cacheIsFilledOnReadAndEvictedOnStaffUpdate`, `cacheIsEvictedOnlyAfterCommitAndNotOnRollback` |
| Cache never used for checkout | `OrderService.placeOrder()` uses `menuItemRepository.findWithLockById` | |
| Abandoned orders don't hold stock | A PREPARING order can be cancelled via the API (`OrderService.cancel()` restores stock once); the automatic online-order expiry was removed with online payment | `cancellingRestoresStock`, `twoStaffMarkingTheSameCounterOrderPaidCreateOnePayment`, `cancelRacingCounterPaymentNeverLeavesAnInconsistentOrder` |
| Concurrent orders safe | `findWithLockById` (`SELECT … FOR UPDATE`, ascending id order), order-row lock `findWithLockByOrderReference`, `@Version` safety net, 409 handler | `concurrentCustomersCannotOversellTheLastItem` |

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
| PAY_AT_COUNTER flow, staff marks paid | `PaymentService.confirmCounterPayment()`, called by the Staff page after a correct OTP | `counterOrderFullLifecycle…` |
| Pay before handover | `OrderService.checkPickup()` refuses OTP check / collection until PAID | `counterOrderFullLifecycle…` |
| Simulated online payment, success/failure | **Removed by design**: NoQ is pay-at-counter only | `onlyCounterPaymentExists` |
| Payment status separate from food status | `CustomerOrder.paymentStatus` vs `status` | |
| No sensitive payment data | No payment inputs at all; staff record counter payments | |
| Another payment type later | add a `Payment` subclass with its own discriminator value | |

## Order ID & OTP (§3E)

| Requirement | Where | Test |
|---|---|---|
| NQ-YYYY-XXXXX, DB-unique | `CustomerOrder.orderReference` `unique = true`, `newOrderReference()` | regex check in `placingAnOrder…` |
| Secure, independent OTP | `SecureRandom` in `PickupCodeService` | |
| OTP hashed, not exposed, not logged | `pickupOtpHash` (BCrypt), DTOs have no OTP, `LoggingAspect` logs no args | `orderDetailsAreStaffOnlyAndNeverContainTheOtp` |
| OTP only to the customer | returned once in 201 response, stored in that browser's `localStorage` | |
| Staff verify, collect only once | `OrderService.verifyPickup()` | `counterOrderFullLifecycle…` |
| Customer OTP check, 5-attempt limit | `GET /api/orders/{ref}` with `X-Pickup-Otp`, `PickupCodeService.checkCustomerAccess()` | `tooManyWrongOtpsLockTheCustomerRead`, `parallelOtpGuessesCannotBypassTheAttemptLimit` |

## Tracking & statuses (§3F)

| Requirement | Where |
|---|---|
| Four statuses: PREPARING → READY_FOR_PICKUP → COLLECTED, plus CANCELLED | `enums/OrderStatus.java` |
| Valid transitions only | `OrderStatus.canMoveTo()` + `OrderService.updateStatus()` (tests `OrderStatusTest` incl. `completeTransitionMatrixIsExactlyTheDocumentedOne`, which checks all 16 pairs) |
| Progress indicator, auto refresh, ready message | Status pill + message on the confirmation, refreshed every 5 s while open (no separate tracking page) |
| Cancellation rules + stock restore (exactly once) | `OrderService.cancel()` via staff *Cancel* (new orders) (test `cancellingRestoresStock`) |

## Kitchen and Staff pages (§4)

| Requirement | Where |
|---|---|
| Separate login, server-side authorization | `SecurityConfig` (KITCHEN / STAFF roles), `staff/login.html`, `StaffUserDetailsService` |
| Kitchen: PREPARING orders with *Ready for Pickup*; Staff: READY_FOR_PICKUP orders only | `staff/kitchen.html` / `staff/dashboard.html`, `staff.js` (`data-mode`) |
| Details, quantities, payment method/status, reference | order cards in `staff.js` |
| Ready for Pickup, verify OTP (records payment), confirm handover → COLLECTED | `staff.js action()`: only the next valid step per card → REST endpoints (`verify-pickup`, `collect`) |
| Refresh without reload | `staff.js` `setInterval(load, 10000)` |
| Kitchen page: Menu Availability + Incoming Orders tabs | `staff/kitchen.html`, `kitchen.js`, `staff.js` (read-only, 10 s refresh) | `kitchenPageHasExactlyTwoTabs` |
| Add / edit / stock / deactivate menu items | **Removed with the admin role**: the menu comes from `data.sql` |
| Server-side validation | `@Valid` request records (`PlaceOrderRequest`, `PickupRequest`, `AvailabilityRequest`) + `GlobalExceptionHandler` |

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
| B. JPA & Hibernate, CRUD | `MenuService` reads and `updateAvailability`, `OrderService` create/read/update (nothing is deleted) |
| C. HQL ×2 | `MenuItemRepository.findPopularAvailable` (popular), `CustomerOrderRepository.findByStatusNewestFirst` (status filter); also 3 HQL queries in `ConceptDemoService` |
| D. Inheritance mapping | `Payment` / `CashPayment` (SINGLE_TABLE), `GET /api/demo/hibernate-session` shows `type(p)` grouping |
| E. SessionFactory, Session, Transaction | `ConceptDemoService.hibernateSessionDemo()`, `GET /api/demo/hibernate-session`; `@Transactional` everywhere else |
| F. Dependency injection, BeanFactory vs ApplicationContext | constructors; `GET /api/demo/spring-container` |
| G. AOP | `LoggingAspect` (console lines `OrderService.placeOrder took 85 ms`) |
| H. Caching | `MenuService`, "Cache MISS" log lines, `/actuator/metrics/cache.gets?tag=cache:menu&tag=result:hit` |

## Unit V concepts (§8–9)

| Concept | Where |
|---|---|
| REST under `/api` (GET, POST, PATCH are used; PUT and DELETE are explained only) | `restcontroller/*`, `docs/API.md` |
| Status codes 200/201/400/401/403/404/409 | `GlobalExceptionHandler`, `SecurityConfig` JSON handlers; all exercised in Postman |
| `@Valid`, structured errors | DTOs, `ApiError` |
| Web services theory, REST vs SOAP | `docs/REPORT.md §5`, `docs/VIVA_GUIDE.md`, report Chapter 8 |
| Separate microservice on 8081 | `noq-notification-service/` |
| RestClient integration, graceful failure | `NotificationClient` (test: `counterOrderFullLifecycle…` runs with the service unreachable) |
| Monolith vs microservices, Spring Cloud | `docs/REPORT.md §8`, `docs/VIVA_GUIDE.md` |

## DevOps, testing, deliverables (§10, §13, §14)

| Deliverable | Where |
|---|---|
| 1–3 Source + Maven | `noq-app/`, `noq-notification-service/`, `pom.xml` files |
| 4 Schema + sample records | `docs/schema-mysql.sql`, `data.sql` |
| 5 Config template | `noq-app/.env.example` |
| 6 Postman | `postman/NoQ.postman_collection.json` (35 requests, auto-tests), `docs/API.md` |
| 7 README | `README.md` |
| 8 Architecture diagrams | `docs/ARCHITECTURE.md` (Mermaid) and `docs/diagrams/*.png` |
| 9 Project report | `docs/REPORT.md` (Markdown) and `docs/TeamNo_NoQ_Report.docx` (Word, for submission) |
| 10 Screenshot checklist | below |
| 11 This table | `docs/REQUIREMENTS_MAP.md` |
| 12 Viva guide | `docs/VIVA_GUIDE.md` |
| 13 Class explanations + order flow | `docs/CODE_WALKTHROUGH.md` |
| Automated tests | 32 tests: `OrderStatusTest`, `MenuApiTest`, `OrderApiTest`, `NotificationControllerTest` |

---

## Screenshots

Real screenshots of the final application, captured in headless Google Chrome from the running app (H2 database, demo data)
while one order was taken from the cart to the handover. They are stored in [`docs/screenshots/`](screenshots/) and are the
same images used in the Word report (Chapter 10). The pickup code visible in them belongs to a throw-away demo order.

| File | Shows |
|---|---|
| `01-customer-menu.png` | Customer menu: title, search, sort, category chips, dish rows with price and availability (Margherita Pizza is "Sold out") |
| `02-menu-filter-beverages.png` | Category filter (Beverages) |
| `03-menu-with-cart-bar.png` | Menu after adding dishes: quantity buttons and the "View cart" bar |
| `04-cart-checkout.png` | Cart and checkout: pay at counter, Place order |
| `05-order-confirmation-otp.png` | Order confirmation: reference, pickup OTP, items, total, status PREPARING, payment PENDING |
| `06-kitchen-incoming-orders.png` | Kitchen page, Incoming Orders tab: one *Ready for Pickup* button per order |
| `07-kitchen-menu-availability.png` | Kitchen page, Menu Availability tab: one switch per dish |
| `08-order-ready-customer.png` | The customer's confirmation after the kitchen pressed *Ready for Pickup* (READY FOR PICKUP) |
| `09-staff-ready-orders-otp.png` | Staff page: ready orders, each with the *Enter pickup OTP* field (a code is being typed) |
| `10-staff-otp-verified.png` | Staff page after a correct OTP: PAID, "OTP verified. Food can be delivered.", *Confirm handover* |
| `11-staff-login.png` | Kitchen / Staff login page |
| `12-order-collected-customer.png` | The customer's confirmation after the handover (COLLECTED) |
| `13-customer-menu-mobile.png` | Customer menu on a 390 px phone screen |
| `14-staff-ready-orders-mobile.png` | Staff page on a 390 px phone screen |

Not captured as files (do these yourself for the report): Postman responses (items 17-20), console log lines (22-24),
`./mvnw clean package` output (25), MySQL Workbench (26) and the GitHub page (27).

## Screenshot checklist

Items marked [x] are covered by the files above; the others need the team (they cannot be produced from the browser).

**Customer**
- [x] 1. Menu home: "Canteen Menu" title, search, sort, category chips, menu cards (`01`)
- [x] 2. Menu filtered by a category (`02`)
- [x] 3. A sold-out card ("Margherita Pizza", visible in `01`)
- [x] 4. Mobile view of the menu (`13`); the "View cart" bar is shown on desktop in `03`
- [x] 5. Cart page with quantity steppers, total and Pay at counter (`04`)
- [ ] 6. Error on checkout (e.g. mark a dish Not available on the Kitchen page, then try to order it from an old cart)
- [x] 7. Order confirmation (pay at counter): reference, items, total, payment status, pickup code (`05`, then `08` and `12` as the status changes)

**Kitchen and Staff**
- [x] 11. Login page (`11`)
- [x] 12. Kitchen, Incoming Orders with a "Ready for Pickup" button (`06`)
- [x] 13. Staff page: the OTP field, then "OTP verified. Food can be delivered." and "Confirm handover" (`09`, `10`)
- [ ] 14. Wrong OTP toast, then the "collected" toast
- [x] 15b. Kitchen, Menu Availability tab (`07`); the greyed-out card on the customer menu is not captured
- [x] 15c. Kitchen, Incoming Orders tab showing just-placed orders (`06`)

**APIs and services**
- [ ] 17. Postman: `POST /api/orders` 201 response with reference + OTP
- [ ] 18. Postman: a 409 (sold out) and a 400 (validation) response
- [ ] 19. Postman: 401 without login, and 403 when `staff` calls a kitchen endpoint
- [ ] 20. Postman collection runner — all requests passing
- [ ] 21. `GET /api/demo/hibernate-session` and `GET /api/demo/spring-container` JSON
- [ ] 22. Console: `LoggingAspect` timing lines and "Cache MISS" appearing only once
- [ ] 23. Two terminals: noq-app on 8080 and notification service on 8081, with the `NOTIFY customer` log line
- [ ] 24. Notification service stopped → warning in noq-app log, order still READY_FOR_PICKUP
- [ ] 25. `./mvnw clean package` → BUILD SUCCESS
- [ ] 26. MySQL Workbench / `SHOW TABLES` and `SELECT * FROM payment` showing the `payment_type` discriminator
- [ ] 27. GitHub repository page with commit history
