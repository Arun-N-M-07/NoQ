package com.noq;

import com.jayway.jsonpath.JsonPath;
import com.noq.entity.CustomerOrder;
import com.noq.enums.OrderStatus;
import com.noq.enums.PaymentMethod;
import com.noq.repository.CustomerOrderRepository;
import com.noq.repository.MenuItemRepository;
import com.noq.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end order rules through the REST API: checkout validation, stock, status transitions,
 * payments, pickup OTP and staff authorization. The notification service is pointed at a dead
 * port on purpose, to prove that READY still works when it is down.
 */
@SpringBootTest(properties = {"noq.notification.base-url=http://localhost:1", "noq.orders.expiry-check-interval=PT24H"})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class OrderApiTest {

    @Autowired MockMvc mvc;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired CustomerOrderRepository orderRepository;
    @Autowired TransactionTemplate tx;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbc;

    private static final RequestPostProcessor STAFF = user("kitchen").roles("STAFF");

    /** Places an order and returns {reference, otp}. */
    private String[] placeOrder(long itemId, int qty, String paymentMethod) throws Exception {
        String body = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuItemId\":" + itemId + ",\"quantity\":" + qty + "}],\"paymentMethod\":\"" + paymentMethod + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new String[]{JsonPath.read(body, "$.orderReference"), JsonPath.read(body, "$.pickupOtp")};
    }

    private void moveTo(String ref, String status) throws Exception {
        mvc.perform(patch("/api/orders/" + ref + "/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status));
    }

    private int popularityOf(long id) {
        return menuItemRepository.findById(id).orElseThrow().getPopularityCount();
    }

    /** Pretends the order was placed 30 minutes ago (the payment window is 15 minutes). */
    private void age(String ref) {
        jdbc.update("update customer_order set created_at = ? where order_reference = ?",
                java.time.LocalDateTime.now().minusMinutes(30), ref);
    }

    private org.springframework.test.web.servlet.ResultActions payOnline(String[] order, boolean success) throws Exception {
        return mvc.perform(post("/api/orders/" + order[0] + "/payments/mock-online").header("X-Pickup-Otp", order[1])
                .contentType(MediaType.APPLICATION_JSON).content("{\"success\":" + success + "}"));
    }

    private String statusOf(String ref) {
        return orderRepository.findByOrderReference(ref).orElseThrow().getStatus().name();
    }

    private int stockOf(long id) {
        return menuItemRepository.findById(id).orElseThrow().getAvailableQuantity();
    }

    @Test
    void placingAnOrderDeductsStockAndReturnsReferenceAndOtp() throws Exception {
        int stockBefore = stockOf(3);
        int popularityBefore = menuItemRepository.findById(3L).orElseThrow().getPopularityCount();

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"menuItemId":3,"quantity":2},{"menuItemId":3,"quantity":1}],
                                 "paymentMethod":"PAY_AT_COUNTER","total":1}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderReference", matchesPattern("NQ-\\d{4}-[A-Z0-9]{5}")))
                .andExpect(jsonPath("$.pickupOtp", matchesPattern("\\d{6}")))
                .andExpect(jsonPath("$.totalAmount").value(60.0)) // 3 x 20.00, browser "total" ignored
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));

        assertThat(stockOf(3)).isEqualTo(stockBefore - 3);
        assertThat(menuItemRepository.findById(3L).orElseThrow().getPopularityCount()).isEqualTo(popularityBefore + 3);
    }

    @Test
    void invalidOrdersAreRejected() throws Exception {
        String[][] cases = {
                {"{\"items\":[],\"paymentMethod\":\"ONLINE\"}", "400"},                                         // empty cart
                {"{\"items\":[{\"menuItemId\":1,\"quantity\":0}],\"paymentMethod\":\"ONLINE\"}", "400"},      // bad quantity
                {"{\"items\":[{\"menuItemId\":1,\"quantity\":1}]}", "400"},                                    // no payment method
                {"{\"items\":[{\"menuItemId\":9999,\"quantity\":1}],\"paymentMethod\":\"ONLINE\"}", "400"},   // unknown item
                {"{\"items\":[{\"menuItemId\":16,\"quantity\":1}],\"paymentMethod\":\"ONLINE\"}", "409"},     // sold out
                {"{\"items\":[{\"menuItemId\":23,\"quantity\":1}],\"paymentMethod\":\"ONLINE\"}", "409"},     // inactive
                {"{\"items\":[{\"menuItemId\":22,\"quantity\":1}],\"paymentMethod\":\"ONLINE\"}", "409"},     // marked sold out
                {"{\"items\":[{\"menuItemId\":12,\"quantity\":16}],\"paymentMethod\":\"ONLINE\"}", "409"},    // more than stock (15)
                {"{\"items\": [ this is not json", "400"},                                                      // malformed JSON
        };
        for (String[] c : cases) {
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(c[0]))
                    .andExpect(status().is(Integer.parseInt(c[1])))
                    .andExpect(jsonPath("$.message").exists());
        }
    }

    @Test
    void duplicateSubmissionIsRejected() throws Exception {
        String body = "{\"items\":[{\"menuItemId\":7,\"quantity\":1}],\"paymentMethod\":\"PAY_AT_COUNTER\",\"idempotencyKey\":\"test-key-123\"}";
        int stockBefore = stockOf(7);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(stockOf(7)).as("stock deducted only once").isEqualTo(stockBefore - 1);
    }

    @Test
    void counterOrderFullLifecycleWithPaymentAndPickup() throws Exception {
        String[] order = placeOrder(1, 1, "PAY_AT_COUNTER");
        String ref = order[0], otp = order[1];

        // invalid transition: cannot jump from PLACED to READY
        mvc.perform(patch("/api/orders/" + ref + "/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"READY\"}")).andExpect(status().isConflict());
        // COMPLETED only through pickup verification
        mvc.perform(patch("/api/orders/" + ref + "/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\"}")).andExpect(status().isBadRequest());

        moveTo(ref, "CONFIRMED");
        moveTo(ref, "PREPARING");
        // cannot cancel once preparation started
        mvc.perform(patch("/api/orders/" + ref + "/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}")).andExpect(status().isConflict());
        moveTo(ref, "READY"); // notification service is down: still succeeds

        String pickup = "{\"otp\":\"" + otp + "\"}";
        // not paid yet
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/orders/" + ref + "/payments/counter-confirmation").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentType").value("CASH"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        mvc.perform(post("/api/orders/" + ref + "/payments/counter-confirmation").with(STAFF))
                .andExpect(status().isConflict()); // duplicate payment

        String wrongOtp = otp.equals("000000") ? "111111" : "000000";
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"" + wrongOtp + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isConflict()); // collected only once
    }

    @Test
    void onlineOrderNeedsSuccessfulMockPaymentBeforeConfirmation() throws Exception {
        String[] order = placeOrder(2, 1, "ONLINE");
        String ref = order[0], otp = order[1];

        mvc.perform(patch("/api/orders/" + ref + "/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isConflict());

        mvc.perform(post("/api/orders/" + ref + "/payments/mock-online").header("X-Pickup-Otp", otp)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"success\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));
        mvc.perform(get("/api/orders/" + ref + "/status")).andExpect(jsonPath("$.paymentStatus").value("FAILED"));

        mvc.perform(post("/api/orders/" + ref + "/payments/mock-online").header("X-Pickup-Otp", otp)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"success\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.transactionReference", matchesPattern("MOCK-.*")));
        mvc.perform(post("/api/orders/" + ref + "/payments/mock-online").header("X-Pickup-Otp", otp)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"success\":true}"))
                .andExpect(status().isConflict()); // already paid

        moveTo(ref, "CONFIRMED");
        // counter confirmation makes no sense for an online order
        mvc.perform(post("/api/orders/" + ref + "/payments/counter-confirmation").with(STAFF)).andExpect(status().isBadRequest());
        // both attempts are stored, as OnlinePayment rows (single-table inheritance)
        Integer attempts = tx.execute(t -> orderRepository.findByOrderReference(ref).orElseThrow().getPayments().size());
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    void cancellingRestoresStock() throws Exception {
        int before = stockOf(5);
        String[] order = placeOrder(5, 2, "PAY_AT_COUNTER");
        assertThat(stockOf(5)).isEqualTo(before - 2);

        mvc.perform(post("/api/orders/" + order[0] + "/cancel").header("X-Pickup-Otp", order[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stockOf(5)).isEqualTo(before);
        assertThat(orderRepository.findByStatusNewestFirst(OrderStatus.CANCELLED)) // HQL query 2
                .anyMatch(o -> o.getOrderReference().equals(order[0]));

        // cancelling again must not restore the stock a second time
        mvc.perform(patch("/api/orders/" + order[0] + "/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/" + order[0] + "/cancel").header("X-Pickup-Otp", order[1])).andExpect(status().isConflict());
        assertThat(stockOf(5)).isEqualTo(before);
        // a cancelled order can never be paid, prepared or collected
        mvc.perform(post("/api/orders/" + order[0] + "/payments/counter-confirmation").with(STAFF)).andExpect(status().isConflict());
        mvc.perform(patch("/api/orders/" + order[0] + "/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PREPARING\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/" + order[0] + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"" + order[1] + "\"}")).andExpect(status().isConflict());
    }

    @Test
    void abandonedOnlineOrderExpiresOnceAndReturnsStock() throws Exception {
        orderService.expireUnpaidOnlineOrders(); // clear aged orders left by other tests before measuring
        int stock = stockOf(8), popularity = popularityOf(8);
        String[] abandoned = placeOrder(8, 2, "ONLINE");
        payOnline(abandoned, false).andExpect(jsonPath("$.paymentStatus").value("FAILED")); // failed attempt still expires
        String[] paid = placeOrder(8, 1, "ONLINE");
        payOnline(paid, true).andExpect(jsonPath("$.paymentStatus").value("PAID"));
        String[] counter = placeOrder(8, 1, "PAY_AT_COUNTER");
        for (String[] o : new String[][]{abandoned, paid, counter}) age(o[0]);

        assertThat(orderService.expireUnpaidOnlineOrders()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(abandoned[0])).isEqualTo("CANCELLED");
        assertThat(statusOf(paid[0])).as("paid orders are never expired").isEqualTo("PLACED");
        assertThat(statusOf(counter[0])).as("counter orders are never expired").isEqualTo("PLACED");
        assertThat(stockOf(8)).isEqualTo(stock - 2);           // only the abandoned 2 came back
        assertThat(popularityOf(8)).isEqualTo(popularity + 2);

        orderService.expireUnpaidOnlineOrders();                 // running again changes nothing
        assertThat(stockOf(8)).isEqualTo(stock - 2);
        payOnline(abandoned, true).andExpect(status().isConflict());
        mvc.perform(patch("/api/orders/" + abandoned[0] + "/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isConflict());
    }

    @Test
    void latePaymentIsRefusedEvenBeforeTheExpiryJobRuns() throws Exception {
        String[] order = placeOrder(8, 1, "ONLINE");
        age(order[0]);
        payOnline(order, true).andExpect(status().isConflict());
        assertThat(orderRepository.findByOrderReference(order[0]).orElseThrow().getPaymentStatus().name()).isNotEqualTo("PAID");
    }

    @Test
    void paymentRacingExpiryLeavesOneConsistentOutcome() throws Exception {
        orderService.expireUnpaidOnlineOrders(); // clear aged orders left by other tests before measuring
        int stock = stockOf(8);
        String[] order = placeOrder(8, 1, "ONLINE");
        age(order[0]);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pay = pool.submit(() -> { start.await(); return payOnline(order, true).andReturn().getResponse().getStatus(); });
        var expire = pool.submit(() -> { start.await(); return orderService.expireUnpaidOnlineOrders(); });
        start.countDown();
        pay.get();
        try { expire.get(); } catch (java.util.concurrent.ExecutionException conflict) { orderService.expireUnpaidOnlineOrders(); }
        pool.shutdown();

        var saved = orderRepository.findByOrderReference(order[0]).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.getPaymentStatus().name()).isNotEqualTo("PAID");
        assertThat(stockOf(8)).as("stock restored exactly once").isEqualTo(stock);
    }

    /** Runs the given requests at the same moment and returns their HTTP statuses. */
    private java.util.List<Integer> inParallel(java.util.List<java.util.concurrent.Callable<Integer>> calls) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(calls.size());
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (var call : calls) futures.add(pool.submit(() -> { start.await(); return call.call(); }));
        start.countDown();
        var statuses = new java.util.ArrayList<Integer>();
        for (var f : futures) statuses.add(f.get());
        pool.shutdown();
        return statuses;
    }

    private int paymentRows(String ref) {
        return tx.execute(t -> orderRepository.findByOrderReference(ref).orElseThrow().getPayments().size());
    }

    @Test
    void twoStaffMarkingTheSameCounterOrderPaidCreateOnePayment() throws Exception {
        String[] order = placeOrder(7, 1, "PAY_AT_COUNTER");
        var statuses = inParallel(java.util.List.of(
                () -> mvc.perform(post("/api/orders/" + order[0] + "/payments/counter-confirmation").with(STAFF)).andReturn().getResponse().getStatus(),
                () -> mvc.perform(post("/api/orders/" + order[0] + "/payments/counter-confirmation").with(STAFF)).andReturn().getResponse().getStatus()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(paymentRows(order[0])).isEqualTo(1);
    }

    @Test
    void doubleClickOnSimulatedPaymentCreatesOneSuccessfulPayment() throws Exception {
        String[] order = placeOrder(7, 1, "ONLINE");
        var statuses = inParallel(java.util.List.of(
                () -> payOnline(order, true).andReturn().getResponse().getStatus(),
                () -> payOnline(order, true).andReturn().getResponse().getStatus()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(paymentRows(order[0])).isEqualTo(1);
        assertThat(orderRepository.findByOrderReference(order[0]).orElseThrow().getPaymentStatus().name()).isEqualTo("PAID");
    }

    @Test
    void cancelRacingCounterPaymentNeverLeavesAnInconsistentOrder() throws Exception {
        for (int round = 0; round < 5; round++) {
            int stockBefore = stockOf(7);
            String[] order = placeOrder(7, 1, "PAY_AT_COUNTER");
            var statuses = inParallel(java.util.List.of(
                    () -> mvc.perform(post("/api/orders/" + order[0] + "/cancel").header("X-Pickup-Otp", order[1])).andReturn().getResponse().getStatus(),
                    () -> mvc.perform(post("/api/orders/" + order[0] + "/payments/counter-confirmation").with(STAFF)).andReturn().getResponse().getStatus()));
            assertThat(statuses).allMatch(code -> code == 200 || code == 409); // never a 500
            var saved = orderRepository.findByOrderReference(order[0]).orElseThrow();
            assertThat(saved.getStatus().name()).isIn("CANCELLED", "PLACED");
            if (saved.getStatus() == OrderStatus.CANCELLED) {
                assertThat(stockOf(7)).as("stock restored exactly once").isEqualTo(stockBefore);
                assertThat(saved.getPaymentStatus().name()).as("cancelled orders are never PAID").isIn("PENDING", "REFUNDED");
            } else {
                assertThat(stockOf(7)).isEqualTo(stockBefore - 1);
                assertThat(saved.getPaymentStatus().name()).isEqualTo("PAID");
            }
            assertThat(paymentRows(order[0])).isEqualTo(saved.getPaymentStatus().name().equals("PENDING") ? 0 : 1);
        }
    }

    @Test
    void parallelOtpGuessesCannotBypassTheAttemptLimit() throws Exception {
        String[] order = placeOrder(18, 1, "PAY_AT_COUNTER");
        String wrong = order[1].equals("999999") ? "888888" : "999999";
        var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
        var start = new java.util.concurrent.CountDownLatch(1);
        var bodies = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < 20; i++) {
            bodies.add(pool.submit(() -> {
                start.await();
                return mvc.perform(get("/api/orders/" + order[0]).header("X-Pickup-Otp", wrong))
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        int checked = 0;
        for (var b : bodies) if (b.get().contains("Invalid pickup code")) checked++;
        pool.shutdown();
        assertThat(checked).as("at most 5 guesses are actually checked").isLessThanOrEqualTo(5);
        mvc.perform(get("/api/orders/" + order[0]).header("X-Pickup-Otp", order[1])).andExpect(status().isForbidden());
    }

    @Test
    void customerNeedsOtpAndNeverSeesIt() throws Exception {
        String[] order = placeOrder(4, 1, "PAY_AT_COUNTER");
        mvc.perform(get("/api/orders/" + order[0]))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/" + order[0]).header("X-Pickup-Otp", order[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Cold Coffee"))
                .andExpect(jsonPath("$.pickupOtp").doesNotExist())
                .andExpect(jsonPath("$.pickupOtpHash").doesNotExist());
        mvc.perform(get("/api/orders/" + order[0] + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"));
        mvc.perform(get("/api/orders/NQ-2026-NOPE0/status")).andExpect(status().isNotFound());
    }

    @Test
    void tooManyWrongOtpsLockPublicAccess() throws Exception {
        String[] order = placeOrder(18, 1, "PAY_AT_COUNTER");
        String wrong = order[1].equals("999999") ? "888888" : "999999";
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/orders/" + order[0]).header("X-Pickup-Otp", wrong)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/orders/" + order[0]).header("X-Pickup-Otp", order[1]))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Too many")));
    }

    @Test
    void staffEndpointsRequireLogin() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/orders/NQ-2026-AAAAA/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READY\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/NQ-2026-AAAAA/verify-pickup").contentType(MediaType.APPLICATION_JSON).content("{\"otp\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").with(STAFF)).andExpect(status().isOk());
        mvc.perform(get("/api/orders").param("status", "PLACED").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PLACED"))));
    }

    @Test
    void concurrentCustomersCannotOversellTheLastItem() throws Exception {
        String created = mvc.perform(post("/api/menu").with(user("admin").roles("ADMIN", "STAFF")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Last Brownie\",\"category\":\"DESSERTS\",\"price\":40,\"availableQuantity\":1}"))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");
        String order = "{\"items\":[{\"menuItemId\":" + id + ",\"quantity\":1}],\"paymentMethod\":\"PAY_AT_COUNTER\"}";

        // 6 customers press "Place order" at the same moment
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        var start = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 6; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(order))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        var statuses = new java.util.ArrayList<Integer>();
        for (var f : results) statuses.add(f.get());
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);   // exactly one winner
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(5);   // everyone else: sold out / changed concurrently
        assertThat(stockOf(id)).isZero();                              // never negative
    }

    @Test
    void staffBoardKeepsAnOldOpenOrderWhenMoreThan100NewerOrdersExist() throws Exception {
        String[] oldOpen = placeOrder(18, 1, "PAY_AT_COUNTER"); // stays PLACED
        for (int i = 0; i < 101; i++) {
            CustomerOrder done = new CustomerOrder();
            done.setOrderReference(String.format("NQ-2026-T%04d", i));
            done.setPickupOtpHash("x");
            done.setPaymentMethod(PaymentMethod.PAY_AT_COUNTER);
            done.setSubtotal(java.math.BigDecimal.ZERO);
            done.setTotalAmount(java.math.BigDecimal.ZERO);
            done.setStatus(OrderStatus.COMPLETED);
            orderRepository.save(done);
        }
        mvc.perform(get("/api/orders").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].orderReference", org.hamcrest.Matchers.hasItem(oldOpen[0])))
                .andExpect(jsonPath("$[?(@.status=='COMPLETED')]").isEmpty());
    }

    @Test
    void hibernateSessionDemoRuns() throws Exception {
        mvc.perform(get("/api/demo/hibernate-session").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionCommitted").value(true))
                .andExpect(jsonPath("$.hqlActiveMenuItems").isNumber());
        mvc.perform(get("/api/demo/spring-container").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationContextIsABeanFactory").value(true))
                .andExpect(jsonPath("$.orderServiceIsAopProxy").value(true));
    }
}
