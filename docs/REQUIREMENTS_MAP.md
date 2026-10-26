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
| Canteen operating status | `HomeController.index()` (`noq.canteen.opens-at/closes-at`) - computed, no longer shown on the simplified menu page |
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
| Popularity increases with orders; popular section sorted; fallback | `reduceStock()`, HQL 1, `HomeController` "House favourites" fallback | `hqlPopularQueryReturns…` |
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

## Staff Orders page (§4)

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
| B. JPA & Hibernate, CRUD | `MenuService` create/read/update/soft-delete, `OrderService` |
| C. HQL ×2 | `MenuItemRepository.findPopularAvailable` (popular), `CustomerOrderRepository.findByStatusNewestFirst` (status filter); also 3 HQL queries in `ConceptDemoService` |
| D. Inheritance mapping | `Payment` / `CashPayment` (SINGLE_TABLE), `GET /api/demo/hibernate-session` shows `type(p)` grouping |
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
| 6 Postman | `postman/NoQ.postman_collection.json` (35 requests, auto-tests), `docs/API.md` |
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

Real screenshots of the running app (headless Chrome, MySQL-backed, demo data) are saved in [`docs/screenshots/`](screenshots/).
**They were taken before the simplification:** rows marked *(old UI)* show pages or layouts that no longer exist and
should be re-captured (order confirmation, staff Orders list).

| File | Shows |
|---|---|
| `01-customer-menu-desktop.png`, `07-customer-menu-mobile.png` | Menu home: tagline, open/closed pill, popular strip, sold-out card *(old UI: still shows the track box)* |
| `02-menu-filter-sort-desktop.png` | Category filter + sort by price |
| `03-cart-desktop.png`, `08-cart-mobile.png` | Cart and payment options |
| `04-demo-online-payment.png` | *(old UI)* simulated online payment page - online payment removed |
| `05-tracking-placed-with-pickup-code.png` | *(old UI)* Tracking page - replaced by the order confirmation |
| `06-tracking-ready.png`, `09-tracking-ready-mobile.png` | *(old UI)* Tracking page "Your order is ready!" |
| `10-staff-login.png` | Staff login |
| `11-staff-order-board.png`, `15-staff-order-board-mobile.png` | *(old UI)* 4-column order board - now one Orders list |
| `12-staff-awaiting-counter-payment.png` | *(old UI)* status filter row - removed |
| `13-admin-menu-management.png` | *(old UI)* admin menu page - removed |
| `14-admin-menu-form-validation-errors.png` | *(old UI)* admin add-item form - removed |
| `16-admin-menu-inactive-and-sold-out-rows.png` | *(old UI)* admin menu table - removed |
| `17-tracking-cancelled.png` | *(old UI)* customer cancel - removed (staff cancel new orders) |
| `18-menu-external-images-blocked-emoji-fallback.png` | All photos blocked: every card shows an emoji tile (mobile) |
| `19-admin-menu-mobile-390.png`, `20-demo-payment-mobile-390.png` | *(old UI)* admin menu and online payment page (both removed), at 390 px |
| `21-tracking-expired-unpaid-online-order.png` | *(old UI)* online order cancelled by the old expiry job (both removed) |

Not captured as files (do these yourself for the report): Postman responses (items 17–20), console log lines (22–24, 24b),
`./mvnw test` output (25), MySQL Workbench (26) and the GitHub page (27).

## Screenshot checklist (original list)

Capture these from the running application (desktop width ~1280 px unless noted) and paste them into the report.

**Customer**
- [ ] 1. Menu home: "Canteen Menu" title, search, sort, category chips, menu cards
- [ ] 2. Menu filtered by a category + a search term, sorted by price
- [ ] 3. A sold-out card ("Margherita Pizza") and a low-stock "Only N left" label
- [ ] 4. Mobile view (375 px) of the menu with the floating "View cart" bar
- [ ] 5. Cart page with quantity steppers, summary and the two payment options
- [ ] 6. Error on checkout (e.g. mark a dish Not available on the Kitchen page, then try to order it from an old cart)
- [ ] 7. Order confirmation (pay at counter): reference, items, total, payment status, pickup code

**Staff**
- [ ] 11. Staff login page
- [ ] 12. Kitchen → Incoming Orders with a "Ready for Pickup" button
- [ ] 13. Staff page: the OTP field (a correct OTP records the payment), "OTP verified. Food can be delivered." and "Confirm handover"
- [ ] 14. Wrong OTP toast, then successful "collected" toast
- [ ] 15b. Kitchen page: Menu Availability tab with a dish marked Not available, and the greyed-out card on the customer menu
- [ ] 15c. Kitchen page: Incoming Orders tab showing a just-placed order
- [ ] 16. Add-item form showing validation errors

**APIs & services**
- [ ] 17. Postman: `POST /api/orders` 201 response with reference + OTP
- [ ] 18. Postman: a 409 (sold out) and a 400 (validation) response
- [ ] 19. Postman: 401 without login, and 403 when `staff` calls a kitchen endpoint
- [ ] 20. Postman collection runner — all requests passing
- [ ] 21. `GET /api/demo/hibernate-session` and `GET /api/demo/spring-container` JSON
- [ ] 22. Console: `LoggingAspect` timing lines and "Cache MISS" appearing only once
- [ ] 23. Two terminals: noq-app on 8080 and notification service on 8081, with the `NOTIFY customer` log line
- [ ] 24. Notification service stopped → warning in noq-app log, order still READY_FOR_PICKUP
- [ ] 25. `./mvnw test` → BUILD SUCCESS
- [ ] 26. MySQL Workbench / `SHOW TABLES` and `SELECT * FROM payment` showing the `payment_type` discriminator
- [ ] 27. GitHub repository page with commit history
