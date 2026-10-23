package com.noq;

import com.jayway.jsonpath.JsonPath;
import com.noq.entity.CustomerOrder;
import com.noq.enums.OrderStatus;
import com.noq.repository.CustomerOrderRepository;
import com.noq.repository.MenuItemRepository;
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
 * port on purpose, to prove that READY_FOR_PICKUP still works when it is down.
 */
@SpringBootTest(properties = {"noq.notification.base-url=http://localhost:1"})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class OrderApiTest {

    @Autowired MockMvc mvc;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired CustomerOrderRepository orderRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final RequestPostProcessor KITCHEN = user("kitchen").roles("KITCHEN"); // Kitchen page: Ready for Pickup
    private static final RequestPostProcessor STAFF = user("staff").roles("STAFF");       // Staff page: payment, OTP, handover

    /** Places an order and returns {reference, otp}. */
    private String[] placeOrder(long itemId, int qty) throws Exception {
        String body = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"menuItemId\":" + itemId + ",\"quantity\":" + qty + "}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new String[]{JsonPath.read(body, "$.orderReference"), JsonPath.read(body, "$.pickupOtp")};
    }

    private void moveTo(String ref, String status) throws Exception {
        mvc.perform(patch("/api/orders/" + ref + "/status").with(KITCHEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status));
    }

    /** What the customer's open confirmation screen does: read the order back with its OTP header. */
    private org.springframework.test.web.servlet.ResultActions readAsCustomer(String ref, String otp) throws Exception {
        return mvc.perform(get("/api/orders/" + ref).header("X-Pickup-Otp", otp));
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
                                 "total":1}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderReference", matchesPattern("NQ-\\d{4}-[A-Z0-9]{5}")))
                .andExpect(jsonPath("$.pickupOtp", matchesPattern("\\d{6}")))
                .andExpect(jsonPath("$.totalAmount").value(60.0)) // 3 x 20.00, browser "total" ignored
                .andExpect(jsonPath("$.status").value("PREPARING")) // the kitchen starts straight away
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                // the confirmation screen's order summary, merged into one line, with the server's prices
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Samosa (2 pcs)"))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].lineTotal").value(60.0));

        assertThat(stockOf(3)).isEqualTo(stockBefore - 3);
        assertThat(menuItemRepository.findById(3L).orElseThrow().getPopularityCount()).isEqualTo(popularityBefore + 3);
    }

    @Test
    void invalidOrdersAreRejected() throws Exception {
        String[][] cases = {
                {"{\"items\":[]}", "400"},                                         // empty cart
                {"{\"items\":[{\"menuItemId\":1,\"quantity\":0}]}", "400"},      // bad quantity
                {"{}", "400"},                                                                                  // no items at all
                {"{\"items\":[{\"menuItemId\":9999,\"quantity\":1}]}", "400"},   // unknown item
                {"{\"items\":[{\"menuItemId\":16,\"quantity\":1}]}", "409"},     // sold out
                {"{\"items\":[{\"menuItemId\":23,\"quantity\":1}]}", "409"},     // inactive
                {"{\"items\":[{\"menuItemId\":22,\"quantity\":1}]}", "409"},     // marked not available
                {"{\"items\":[{\"menuItemId\":12,\"quantity\":16}]}", "409"},    // more than stock (15)
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
        String body = "{\"items\":[{\"menuItemId\":7,\"quantity\":1}],\"idempotencyKey\":\"test-key-123\"}";
        int stockBefore = stockOf(7);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(stockOf(7)).as("stock deducted only once").isEqualTo(stockBefore - 1);
    }

    @Test
    void counterOrderFullLifecycleWithPaymentAndPickup() throws Exception {
        String[] order = placeOrder(1, 1);
        String ref = order[0], otp = order[1];

        String pickup = "{\"otp\":\"" + otp + "\"}";
        // COLLECTED only through the OTP check, never by a status change; not before the kitchen marked it ready
        mvc.perform(patch("/api/orders/" + ref + "/status").with(KITCHEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COLLECTED\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/orders").param("status", "READY_FOR_PICKUP").with(STAFF)) // Staff page: not shown yet
                .andExpect(jsonPath("$[*].orderReference", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(ref))));

        moveTo(ref, "READY_FOR_PICKUP"); // Kitchen: "Ready for Pickup" (notification service is down: still succeeds)
        mvc.perform(patch("/api/orders/" + ref + "/status").with(KITCHEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"READY_FOR_PICKUP\"}")).andExpect(status().isConflict()); // pressing it twice is rejected
        mvc.perform(patch("/api/orders/" + ref + "/status").with(KITCHEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}")).andExpect(status().isConflict()); // too late to cancel
        mvc.perform(get("/api/orders").param("status", "READY_FOR_PICKUP").with(STAFF)) // Staff page now shows it
                .andExpect(jsonPath("$[*].orderReference", org.hamcrest.Matchers.hasItem(ref)));
        mvc.perform(get("/api/orders/" + ref).header("X-Pickup-Otp", otp)) // customer's open confirmation
                .andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"));

        // not paid yet: a wrong OTP is rejected, the right one is accepted, and neither records a payment
        String wrongOtp = otp.equals("000000") ? "111111" : "000000";
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"" + wrongOtp + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
        mvc.perform(post("/api/orders/" + ref + "/collect").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isConflict()); // no handover until the payment is recorded

        mvc.perform(post("/api/orders/" + ref + "/payments/counter-confirmation").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentType").value("CASH"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        mvc.perform(post("/api/orders/" + ref + "/payments/counter-confirmation").with(STAFF))
                .andExpect(status().isConflict()); // duplicate payment

        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"" + wrongOtp + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/" + ref + "/collect").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otp\":\"" + wrongOtp + "\"}"))
                .andExpect(status().isBadRequest()); // the handover step checks the OTP itself, too
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PICKUP")); // step 1 only checks
        mvc.perform(post("/api/orders/" + ref + "/collect").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COLLECTED"));
        mvc.perform(post("/api/orders/" + ref + "/collect").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isConflict()); // collected only once
        mvc.perform(post("/api/orders/" + ref + "/verify-pickup").with(STAFF).contentType(MediaType.APPLICATION_JSON).content(pickup))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/orders").param("status", "READY_FOR_PICKUP").with(STAFF)) // collected orders leave the Staff page
                .andExpect(jsonPath("$[*].orderReference", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(ref))));
        mvc.perform(get("/api/orders/" + ref).header("X-Pickup-Otp", otp)) // what the customer's open confirmation reads
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COLLECTED"))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void cancellingRestoresStock() throws Exception {
        int before = stockOf(5);
        String[] order = placeOrder(5, 2);
        assertThat(stockOf(5)).isEqualTo(before - 2);

        moveTo(order[0], "CANCELLED"); // cancelled (API) while still being prepared
        assertThat(stockOf(5)).isEqualTo(before);
        assertThat(orderRepository.findByStatusNewestFirst(OrderStatus.CANCELLED)) // HQL query 2
                .anyMatch(o -> o.getOrderReference().equals(order[0]));

        // cancelling again must not restore the stock a second time
        mvc.perform(patch("/api/orders/" + order[0] + "/status").with(KITCHEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}")).andExpect(status().isConflict());
        assertThat(stockOf(5)).isEqualTo(before);
        // a cancelled order can never be paid, made ready or collected
        mvc.perform(post("/api/orders/" + order[0] + "/payments/counter-confirmation").with(STAFF)).andExpect(status().isConflict());
        mvc.perform(patch("/api/orders/" + order[0] + "/status").with(KITCHEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"READY_FOR_PICKUP\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/" + order[0] + "/collect").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"" + order[1] + "\"}")).andExpect(status().isConflict());
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
        String[] order = placeOrder(7, 1);
        var statuses = inParallel(java.util.List.of(
                () -> mvc.perform(post("/api/orders/" + order[0] + "/payments/counter-confirmation").with(STAFF)).andReturn().getResponse().getStatus(),
                () -> mvc.perform(post("/api/orders/" + order[0] + "/payments/counter-confirmation").with(STAFF)).andReturn().getResponse().getStatus()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(paymentRows(order[0])).isEqualTo(1);
    }

    @Test
    void cancelRacingCounterPaymentNeverLeavesAnInconsistentOrder() throws Exception {
        for (int round = 0; round < 5; round++) {
            int stockBefore = stockOf(7);
            String[] order = placeOrder(7, 1);
            var statuses = inParallel(java.util.List.of(
                    () -> mvc.perform(patch("/api/orders/" + order[0] + "/status").with(KITCHEN).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"CANCELLED\"}")).andReturn().getResponse().getStatus(),
                    () -> mvc.perform(post("/api/orders/" + order[0] + "/payments/counter-confirmation").with(STAFF)).andReturn().getResponse().getStatus()));
            assertThat(statuses).allMatch(code -> code == 200 || code == 409); // never a 500
            var saved = orderRepository.findByOrderReference(order[0]).orElseThrow();
            assertThat(saved.getStatus().name()).isIn("CANCELLED", "PREPARING");
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
        String[] order = placeOrder(18, 1);
        String[] wrong = {order[0], order[1].equals("999999") ? "888888" : "999999"};
        var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
        var start = new java.util.concurrent.CountDownLatch(1);
        var bodies = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < 20; i++) {
            bodies.add(pool.submit(() -> {
                start.await();
                return readAsCustomer(wrong[0], wrong[1]).andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        int checked = 0;
        for (var b : bodies) if (b.get().contains("Invalid pickup code")) checked++;
        pool.shutdown();
        assertThat(checked).as("at most 5 guesses are actually checked").isLessThanOrEqualTo(5);
        readAsCustomer(order[0], order[1]).andExpect(status().isForbidden());
    }

    @Test
    void tooManyWrongOtpsLockTheCustomerRead() throws Exception {
        String[] order = placeOrder(18, 1);
        String[] wrong = {order[0], order[1].equals("999999") ? "888888" : "999999"};
        for (int i = 0; i < 5; i++) {
            readAsCustomer(wrong[0], wrong[1]).andExpect(status().isForbidden());
        }
        readAsCustomer(order[0], order[1])
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Too many")));
    }

    @Test
    void customerReadsOwnOrderOnlyWithOtpAndNeverSeesIt() throws Exception {
        String[] order = placeOrder(4, 1);
        String wrong = order[1].equals("999999") ? "888888" : "999999";
        mvc.perform(get("/api/orders/" + order[0])).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/" + order[0]).header("X-Pickup-Otp", wrong)).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/" + order[0]).header("X-Pickup-Otp", order[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(jsonPath("$.items[0].name").value("Cold Coffee"))
                .andExpect(jsonPath("$.pickupOtp").doesNotExist())
                .andExpect(jsonPath("$.pickupOtpHash").doesNotExist());
        mvc.perform(get("/api/orders/" + order[0]).with(STAFF)).andExpect(status().isOk()); // staff need no OTP
        mvc.perform(get("/api/orders/NQ-2026-NOPE0").with(STAFF)).andExpect(status().isNotFound());
    }

    @Test
    void staffOrdersPageIsOneList() throws Exception {
        mvc.perform(get("/staff").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("<h1>Ready for pickup</h1>"),
                        org.hamcrest.Matchers.containsString("data-orders"),
                        org.hamcrest.Matchers.containsString("data-mode=\"staff\""),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("column")))));
    }

    @Test
    void trackingAndPaymentPagesAreGone() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/track"))));
        mvc.perform(get("/track").with(STAFF)).andExpect(status().isNotFound());
        mvc.perform(get("/pay/NQ-2026-AAAAA").with(STAFF)).andExpect(status().isNotFound());
        // the old customer tracking/cancel API is closed to the public
        mvc.perform(get("/api/orders/NQ-2026-AAAAA/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/NQ-2026-AAAAA/cancel").header("X-Pickup-Otp", "123456")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyCounterPaymentExists() throws Exception {
        String[] order = placeOrder(4, 1);
        mvc.perform(post("/api/orders/" + order[0] + "/payments/mock-online").header("X-Pickup-Otp", order[1])
                .contentType(MediaType.APPLICATION_JSON).content("{\"success\":true}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select payment_method from customer_order where order_reference = ?", String.class, order[0]))
                .isEqualTo("PAY_AT_COUNTER");
        readAsCustomer(order[0], order[1]).andExpect(jsonPath("$.paymentStatus").value("PENDING"));
        mvc.perform(get("/cart")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("Pay at counter"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsStringIgnoringCase("online")))));
    }

    @Test
    void staffEndpointsRequireLogin() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/orders/NQ-2026-AAAAA/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"READY_FOR_PICKUP\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/NQ-2026-AAAAA/verify-pickup").contentType(MediaType.APPLICATION_JSON).content("{\"otp\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").with(STAFF)).andExpect(status().isOk());
        mvc.perform(post("/api/orders/NQ-2026-AAAAA/collect").contentType(MediaType.APPLICATION_JSON).content("{\"otp\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        // each login can only do its own job
        mvc.perform(patch("/api/orders/NQ-2026-AAAAA/status").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"READY_FOR_PICKUP\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/NQ-2026-AAAAA/collect").with(KITCHEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"123456\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/staff").with(KITCHEN)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/orders").param("status", "PREPARING").with(STAFF)) // Kitchen page list
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PREPARING"))));
    }

    @Test
    void concurrentCustomersCannotOversellTheLastItem() throws Exception {
        long id = 21; // Gulab Jamun: only one left
        jdbc.update("update menu_item set available_quantity = 1 where id = ?", id);
        String order = "{\"items\":[{\"menuItemId\":" + id + ",\"quantity\":1}]}";

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
        String[] oldOpen = placeOrder(18, 1); // stays PREPARING
        for (int i = 0; i < 101; i++) {
            CustomerOrder done = new CustomerOrder();
            done.setOrderReference(String.format("NQ-2026-T%04d", i));
            done.setPickupOtpHash("x");
            done.setSubtotal(java.math.BigDecimal.ZERO);
            done.setTotalAmount(java.math.BigDecimal.ZERO);
            done.setStatus(OrderStatus.COLLECTED);
            orderRepository.save(done);
        }
        mvc.perform(get("/api/orders").with(STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].orderReference", org.hamcrest.Matchers.hasItem(oldOpen[0])))
                .andExpect(jsonPath("$[?(@.status=='COLLECTED')]").isEmpty());
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
