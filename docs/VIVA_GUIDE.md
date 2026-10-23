# Viva Preparation Guide

Short, honest answers you can say out loud, each tied to a place in the NoQ code you can open and show.
Split the topics between the three team members, but everyone should be able to explain the order flow
(`CODE_WALKTHROUGH.md` Part 1).

---

## Part A — The project itself

**Q: What problem does NoQ solve?**
Long queues at the college canteen counter. Students order ahead from their phone, get a pickup code, and only
come to the counter when the food is ready. Staff get one organised Orders list instead of shouted orders.

**Q: Walk us through one order.**
Menu page → cart in `localStorage` → `POST /api/orders` → `OrderService.placeOrder()` validates every item against
the database, deducts stock, snapshots prices, generates reference + OTP, saves in one transaction → 201 with the
OTP → the cart page shows the order confirmation (reference, items, total, payment status, OTP, current status) → on
the Kitchen page the new order is PREPARING → the kitchen clicks *Ready for Pickup* → READY_FOR_PICKUP (microservice
notified), so it moves to the Staff page → staff verify the customer's OTP (a correct OTP records the payment: "OTP verified. Food can be
delivered.") and confirm the handover → COLLECTED. The customer's open confirmation re-reads the order every 5 s:
PREPARING → READY FOR PICKUP → "Collected — enjoy your meal!".

**Q: Why is there no order-tracking page?**
To keep the project small: the customer only needs the confirmation screen (it is kept in `localStorage`, so a refresh
keeps the pickup code) and comes to the counter with the code. While it is open it polls `GET /api/orders/{ref}` with
the OTP header every 5 s, so it shows the real, saved status (Ready, Collected) without a separate page.

**Q: Why don't you trust the price the browser sends?**
Anyone can edit JavaScript or send a request from Postman. The request only contains item ids and quantities; prices
and totals come from the database. Our test sends a fake `"total": 1` and checks the server still charges ₹60.

**Q: How do you stop two students buying the last samosa?**
Checkout first locks the menu rows with `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`, in ascending id order), so
buyers queue up; the second buyer then sees stock 0 and gets 409 "sold out". The same idea protects an order row: every
action that changes an order (pay, status change, cancel, pickup) locks that order first. Without it, MySQL/InnoDB deadlocked:
inserting `order_item` takes a shared lock on the `menu_item` row (foreign key check) and the later stock `UPDATE` needs
an exclusive lock, so two buyers each waited for the other - MySQL killed one and the API answered 503. H2 does not
behave like that, which is why the tests must also be run on MySQL. `@Version` stays as a safety net: Hibernate's update
is `UPDATE menu_item SET … , version = version + 1 WHERE id = ? AND version = ?`, and a lost race becomes a 409. The test
`concurrentCustomersCannotOversellTheLastItem` fires 6 parallel orders for 1 item: exactly one 201, five 409s, stock 0.

**Q: How is the pickup OTP kept secret?**
Generated with `SecureRandom` (unpredictable), independent of the order number, stored only as a BCrypt hash (like a
password), sent once in the 201 response, never in any list endpoint, never logged (the AOP aspect logs no arguments),
and only kept in the customer's own browser. The customer endpoint that needs it (reading the order back) locks an order after 5
wrong codes; no response ever includes it.

**Q: How does payment work?**
Only at the counter: the customer pays cash or UPI when collecting, staff verify the OTP and only then is a `CashPayment` row
saved (wrong OTP = no payment). The handover (`collect`) is refused until the order is PAID. There is no online payment, so no card/UPI data ever
reaches NoQ.

**Q: What happens if the notification service is down?**
The order is already committed as READY_FOR_PICKUP before we call it (`@TransactionalEventListener(AFTER_COMMIT)`), the call has a
2-second timeout, and failures are caught and logged. The Orders list still shows the order as Ready. Limitation: the
notification is lost — a message broker with retries would fix that.

---

## Part B — Unit IV: Advanced Frameworks

### Spring & Spring Boot
**Q: What is Spring Boot and what does it add to Spring?**
Spring is the framework (IoC container, MVC, data access, transactions…). Spring Boot adds *auto-configuration*
(sees MySQL driver + JPA on the classpath and configures a DataSource, EntityManagerFactory, transaction manager),
*starters* (one dependency pulls a tested set of libraries), an *embedded Tomcat* (runnable JAR) and *Actuator*.

**Q: What does `@SpringBootApplication` do?** (`NoqApplication`)
It combines `@Configuration`, `@EnableAutoConfiguration` and `@ComponentScan` — scans `com.noq` for beans and applies auto-configuration.

### MVC
**Q: Explain MVC in your project.**
- **Model** — data passed to the view (`model.addAttribute("menu", …)`) and the entities/DTOs behind it.
- **View** — Thymeleaf templates in `templates/` (`index.html`).
- **Controller** — `HomeController`, `StaffController` receive requests and choose a view.

**Q: Request lifecycle?**
Browser → embedded Tomcat → Spring Security filter chain → `DispatcherServlet` → handler mapping finds
`HomeController.index()` → controller calls `MenuService` (through its proxy: logging aspect, transaction, cache) →
repository → Hibernate → MySQL → back up → controller returns `"index"` → `ThymeleafViewResolver` renders
`templates/index.html` with the model → HTML response. For `@RestController` the return object is converted to JSON
by Jackson instead of rendering a view.

**Q: `@Controller` vs `@RestController`?**
`@Controller` methods return view names (HTML). `@RestController` = `@Controller` + `@ResponseBody`: return values are
written directly to the response as JSON.

**Q: How does validation work?**
Request records carry Bean Validation annotations (`PlaceOrderRequest`: `@NotEmpty`, quantity `@Min(1) @Max(20)`;
`PickupRequest`: `@Pattern("\\d{6}")`; `AvailabilityRequest`: `@NotNull`). `@Valid` on the controller parameter runs them;
a failure throws `MethodArgumentNotValidException`, which `GlobalExceptionHandler` turns into **400** with one message
per field. Business rules (stock, status, OTP) are checked again in the services.

### Dependency Injection & IoC
**Q: What is IoC / DI?**
Inversion of Control: objects don't create their dependencies; the container creates them and *injects* them.
NoQ uses **constructor injection**: `OrderService(CustomerOrderRepository, MenuItemRepository, MenuService, …)`.
Benefits: fields can be `final`, dependencies are explicit, easy to test.

**Q: BeanFactory vs ApplicationContext?**
`BeanFactory` is the basic IoC container interface: creates beans lazily, wires them, `getBean()`.
`ApplicationContext` extends it and adds eager singleton creation, events (`publishEvent` — we use it for
`OrderReadyEvent`), internationalisation, resource loading, environment/profiles and automatic registration of
post-processors (needed for AOP, `@Transactional`). Show `GET /api/demo/spring-container`:
`applicationContextIsABeanFactory: true`, ~460 beans, `OrderService` is a singleton and a CGLIB proxy.

**Q: Bean scope?** Default singleton — one shared instance per container (the demo endpoint proves it).

### JPA & Hibernate
**Q: What is ORM?** Mapping Java objects to relational tables so you work with objects and the framework writes SQL.
**JPA** is the specification (annotations + `EntityManager` API). **Hibernate** is the implementation we use.
**Spring Data JPA** generates repository implementations.

**Q: How do entities map to tables?**
`@Entity` class → table (`@Table(name = "menu_item")`), field → column (`@Column`), `@Id` + `@GeneratedValue(IDENTITY)`
→ MySQL `AUTO_INCREMENT` primary key, `@Enumerated(STRING)` → text value, `@ManyToOne @JoinColumn(name="order_id")` →
foreign key, `@OneToMany(mappedBy=…)` → the inverse side, `@Version` → optimistic lock column. See `docs/schema-mysql.sql`,
which Hibernate generated from these annotations.

**Q: Relationships in NoQ?**
`CustomerOrder` 1—* `OrderItem` (cascade ALL, so saving the order saves its items), `OrderItem` *—1 `MenuItem`,
`CustomerOrder` 1—* `Payment`. `FetchType.LAZY` on `@ManyToOne` avoids loading what we don't need.

**Q: CRUD?** Create: `orderRepository.save(order)` (INSERT order + items by cascade, payments by `CashPayment`). Read:
`getMenu`/`getItem`/`findByStatusNewestFirst`. Update: `MenuService.updateAvailability` and status changes change a
managed entity → Hibernate *dirty checking* issues UPDATE at commit, no `save()` needed. Delete: nothing is deleted -
orders are history, and a dish is hidden with `active = false` (old `order_item` rows reference it by foreign key).

**Q: N+1 problem?** Loading 100 orders then touching each order's items lazily would run 101 queries.
`hibernate.default_batch_fetch_size=50` makes Hibernate load items for 50 orders per query.

**Q: open-in-view=false?** All database work finishes inside `@Transactional` service methods; views never trigger lazy loading.

### HQL
**Q: What is HQL?** Hibernate Query Language: SQL-like but uses **entity and field names**, not tables and columns.
Hibernate translates it to the database's SQL dialect. JPQL is the standard subset.

**Q: Show your HQL queries.**
1. `MenuItemRepository.findPopularAvailable`:
   `select m from MenuItem m where m.active = true and m.available = true and m.availableQuantity > 0 order by m.popularityCount desc, m.id asc`
2. `CustomerOrderRepository.findByStatusNewestFirst`:
   `select o from CustomerOrder o where o.status = :status order by o.createdAt desc` (named parameter `:status`)
3. In `ConceptDemoService`: `select type(p), count(p) from Payment p group by type(p)` — uses the inheritance discriminator.

Run them: home page popular strip / `GET /api/menu/popular`; `GET /api/orders?status=PREPARING` (Kitchen page) / `READY_FOR_PICKUP` (Staff page).

### Inheritance mapping
**Q: Explain your inheritance mapping.**
Abstract `Payment` with `@Inheritance(strategy = SINGLE_TABLE)` and `@DiscriminatorColumn(name = "payment_type")`.
`CashPayment` (`@DiscriminatorValue("CASH")`, `receivedBy`). All rows go into one `payment` table; Hibernate fills
`payment_type` on insert and uses it to create the right subclass on read. A new way to pay would just be another
subclass with its own discriminator value (its own columns would be NULL for cash rows).

**Q: Other strategies?**
- `SINGLE_TABLE` — one table, fastest (no joins), nullable subclass columns. *Our choice.*
- `JOINED` — parent table + one table per subclass joined by PK; normalised but needs joins.
- `TABLE_PER_CLASS` — one full table per concrete class; polymorphic queries need UNION.
- `@MappedSuperclass` — shares fields only; the parent is not an entity and can't be queried.

### SessionFactory, Session, Transaction
**Q: Explain them.** (`ConceptDemoService.hibernateSessionDemo`, `GET /api/demo/hibernate-session`)
- **SessionFactory** — built once at start-up from all mappings; thread-safe, expensive, one per database.
  JPA name: `EntityManagerFactory`. We *unwrap* the one Spring Boot already built — we don't create a second one.
- **Session** — a short-lived, non-thread-safe unit of work holding the *persistence context* (first-level cache of
  loaded entities, dirty checking). JPA name: `EntityManager`.
- **Transaction** — groups operations so they all commit or all roll back (atomicity). `tx.commit()` / `tx.rollback()`.

**Q: How does `@Transactional` relate?** Spring's proxy begins a transaction before the method, binds a Session/EntityManager
to the thread, commits on success and rolls back on a runtime exception — exactly the manual code in the demo, without boilerplate.

**Q: Entity states?** Transient (new, unknown to Hibernate) → Persistent/managed (in a session; changes auto-saved) → Detached
(session closed) → Removed.

### AOP
**Q: What is AOP and why?**
Aspect-Oriented Programming handles *cross-cutting concerns* (logging, security, transactions, caching) that would
otherwise be copy-pasted into every method. `LoggingAspect` logs execution time and failures of every public service
method without a single log line inside the services.

**Q: Terms?**
- **Aspect** — the class (`LoggingAspect`, `@Aspect`).
- **Advice** — the code that runs (`@Around logExecution`). Others: `@Before`, `@After`, `@AfterReturning`, `@AfterThrowing`.
- **Pointcut** — where it applies: `execution(public * com.noq.service..*(..))`.
- **Join point** — a method execution matched by the pointcut (`ProceedingJoinPoint`).
- **Weaving** — Spring does it at runtime with proxies (JDK dynamic or CGLIB).

**Q: Why don't you log arguments?** They can contain the pickup OTP.

### Caching
**Q: How does your cache work?**
`@Cacheable(cacheNames = "menu", key = "#category == null ? 'ALL' : #category.name()")` on `MenuService.getMenu`.
First call: method runs, logs "Cache MISS", result stored in Caffeine. Next calls with the same key return the stored
list — no SQL. After every staff change and every order/cancel (stock or popularity changed), `MenuService.evictMenuCaches()`
registers a `TransactionSynchronization` that clears both caches in `afterCommit()`. Clearing *before* the commit would let
another request re-cache the old rows; on rollback nothing is cleared because nothing changed.
Caffeine also expires entries after 60 s. Show `/actuator/metrics/cache.gets?tag=cache:menu&tag=result:hit`.

**Q: Why not use the cache at checkout?** It may be up to 60 s old. The database is the source of truth: checkout always
reads stock with `findById` inside the transaction.

### Spring Security (supporting topic)
Form login for staff pages, HTTP Basic for Postman, BCrypt password hashes in `staff_user`, URL rules in
`SecurityConfig` (KITCHEN vs STAFF; customers need no login). Hiding a button is not security — the server checks every request. 401 = who are you?
403 = I know you, but you're not allowed.

---

## Part C — Unit V: Web Services

**Q: What is a web service?**
Software that exposes functionality over a network using standard web protocols (HTTP) and formats (JSON/XML) so
programs written in any language can call it. NoQ's `/api/**` and the notification service are web services.

**Q: What is REST?**
REpresentational State Transfer — an architectural style: everything is a **resource** with a URL
(`/api/orders/NQ-2026-DEBCE`), manipulated with standard **HTTP methods**, using **representations** (JSON),
**stateless** requests (each carries what it needs, e.g. Basic auth header or OTP header), uniform interface,
cacheable responses, layered system.

**Q: HTTP methods in NoQ?**

| Method | Meaning | Safe | Idempotent | NoQ example |
|---|---|---|---|---|
| GET | read | yes | yes | `GET /api/menu` |
| POST | create / action | no | no | `POST /api/orders` → 201 |
| PUT | replace whole resource | no | yes | not used in NoQ |
| PATCH | partial update | no | not necessarily | `PATCH /api/orders/{ref}/status` |
| DELETE | remove | no | yes | not used (nothing is deleted) |

POST isn't idempotent, so we add an `idempotencyKey` to stop double-click duplicates.

**Q: Status codes?** 2xx success (200, 201 + `Location`, 204), 4xx client error (400 validation, 401 not logged in,
403 forbidden, 404 not found, 409 conflict with current state), 5xx server error (500, 503). See `docs/API.md`.

**Q: Why JSON?** Lightweight, human-readable, native to JavaScript, supported by every language. Jackson converts Java
records ↔ JSON automatically in Spring.

**Q: REST vs SOAP?**

| | REST | SOAP |
|---|---|---|
| Type | Architectural style | Protocol (W3C standard) |
| Message format | Any — usually JSON | XML envelope only |
| Transport | HTTP | HTTP, SMTP, JMS … |
| Contract | Optional (OpenAPI) | Mandatory WSDL |
| Operations | HTTP methods on resource URLs | Named operations in WSDL, usually all POST |
| Weight | Light, cacheable | Heavier, more overhead |
| Built-in standards | Uses HTTP features | WS-Security, WS-AtomicTransaction, WS-ReliableMessaging |
| Typical use | Web/mobile apps, public APIs, microservices | Banking, telecom, enterprise integrations needing strict contracts |

**Q: Why did you choose REST?** Browser JavaScript and Postman call it directly, JSON is small, and Spring MVC makes it trivial.

**Q: How do you validate REST input?** `@Valid @RequestBody` + Bean Validation annotations on DTO records; failures go to
`GlobalExceptionHandler` (`@RestControllerAdvice`) which returns a consistent `ApiError` JSON with `fieldErrors`.

### Microservices
**Q: Monolith vs microservices?**

| Monolith (`noq-app`) | Microservices (`noq-notification-service`) |
|---|---|
| One deployable unit, one process | Many small services, each deployable alone |
| In-process method calls | Network calls (HTTP/JSON, messaging) |
| One database | Ideally a database per service |
| Simple to build, test and deploy | Scale and deploy parts independently, different tech per service |
| A bug can take everything down | Failures isolated — but network failures must be handled |

NoQ is a deliberate hybrid: the core stays a monolith (simple for a small team) and one clearly separate concern
(notifications) is a microservice to demonstrate the idea.

**Q: How do the two services communicate?** `NotificationClient` uses Spring's `RestClient` to `POST` JSON to
`http://localhost:8081/api/notifications/order-ready`. Base URL comes from configuration (`NOTIFICATION_URL`).

**Q: Limitation of synchronous calls?** If the other service is slow or down, the caller waits (we cap it at 2 s)
and the message is lost. Fix: asynchronous messaging (RabbitMQ/Kafka) with retries, or the outbox pattern.

**Q: What is Spring Cloud?** A set of projects for building distributed systems on Spring Boot:
- **Service discovery** — Eureka / Consul: services register themselves, callers look them up by name instead of hard-coded URLs.
- **Centralised configuration** — Spring Cloud Config Server: one Git-backed place for all services' properties.
- **API Gateway** — Spring Cloud Gateway: single entry point that routes, authenticates, rate-limits.
- **Load balancing** — Spring Cloud LoadBalancer.
- **Resilience** — circuit breakers, retries, timeouts (Resilience4j via Spring Cloud CircuitBreaker).
- **Distributed tracing** — Micrometer Tracing / Zipkin.
- **Messaging** — Spring Cloud Stream.

We didn't need these for two services on one machine; with more services we'd add discovery + gateway first, and a
circuit breaker around `NotificationClient`.

---

## Part D — DevOps & tooling

**Q: Why Maven?** Declarative build: dependencies with transitive resolution from Maven Central, standard folder
layout, lifecycle (`compile → test → package → verify → install`), plugins (Spring Boot plugin builds a runnable fat JAR).
The parent POM fixes compatible versions. The wrapper `mvnw` pins Maven 3.9 so everyone builds identically.

**Q: Why two pom.xml files and a root one?** Each app is independent (own parent, own JAR); the root aggregator just lets
`./mvnw verify` build both.

**Q: What is CI/CD?** CI: every push is built and tested automatically (GitHub Actions running `./mvnw -B verify`).
CD: tested builds are automatically packaged and deployed. DevOps = culture + automation that joins development and
operations: version control, automated build/test, monitoring (Actuator), configuration via environment variables.

**Q: How do you keep secrets out of Git?** Passwords come from environment variables or `noq-app/.env`, which is in
`.gitignore`; only `.env.example` with placeholders is committed.

**Q: What do your tests cover?** 32 automated tests: unit test of status rules, MockMvc integration tests for menu and order
APIs (validation, stock, transitions, payments, OTP, authorization, caching, concurrency), and the microservice endpoint.
They run on H2, so no MySQL is needed in CI.

---

## Likely "trick" questions

- *Why `BigDecimal`?* `0.1 + 0.2 != 0.3` in `double`; money must be exact.
- *Why store item name/price on `OrderItem`?* So later menu edits don't change old bills.
- *Why `@Enumerated(STRING)` not ORDINAL?* Reordering the enum would silently corrupt old data with ordinals.
- *What if two staff record the payment together?* the order row is locked (`FOR UPDATE`) before it is changed, so the second request waits, then sees PAID and gets 409.
- *Can a collected order go back to PREPARING?* No — `OrderStatus.canMoveTo()` returns false for every move out of COLLECTED/CANCELLED.
- *Why verify the OTP and then confirm separately?* Staff see "OTP verified" before handing over the food; the confirm call (`collect`) checks the OTP again on the server, so skipping step 1 never helps.
- *Why polling (Orders list every 10 s, confirmation every 5 s) and not WebSockets?* Simple, works everywhere, a few seconds' delay is fine for food. WebSockets/SSE would push instantly.
- *Why two simple lists?* The kitchen only needs orders to cook (one *Ready for Pickup* button each); the counter only needs ready orders (pay, OTP, handover). The server re-checks every transition (`OrderStatus.canMoveTo()`).
- *Why is the order reference not the secret?* It's printed and shouted across the counter; the separate random OTP is the secret.
- *Why is `pickupOtpHash` BCrypt and not SHA-256?* BCrypt is slow and salted, so a leaked database can't be reversed quickly.
- *What if a student orders and never comes?* Stock was reserved at checkout. A PREPARING order can be cancelled through
  the API (`cancel()` returns the stock exactly once); a ready order stays on the Staff page.
- *Can someone brute-force the 6-digit OTP?* The only public endpoint that checks it (reading the order back) allows 5 tries per order; pickup verification needs a staff login. The attempt is counted with an atomic
  `incrementAndGet()` **before** the slow BCrypt check, so 100 parallel guesses can't all get past the limit together
  (a test proves this; the first version of the code had exactly that bug).
- *Why are enum columns `varchar(20)` (`columnDefinition`) and not MySQL `ENUM`?* Hibernate creates `ENUM(...)` or a
  `CHECK (... IN ...)` constraint by default, and `ddl-auto=update` never updates it, so adding a status in Java would make
  inserts fail. Plain VARCHAR avoids that; the Java enum still rejects unknown values.
- *What does `spring.jpa.hibernate.ddl-auto=update` do?* Creates missing tables/columns at start-up from the entities. In production
  you'd use `validate` with a migration tool (Flyway/Liquibase).
