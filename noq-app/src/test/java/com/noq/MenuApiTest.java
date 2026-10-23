package com.noq;

import com.noq.repository.MenuItemRepository;
import com.noq.service.MenuService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Menu REST API, kitchen availability switch, logins/roles, HQL query 1 and caching. Runs on the in-memory H2 profile. */
@SpringBootTest(properties = {"noq.notification.base-url=http://localhost:1"})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class MenuApiTest {

    @Autowired MockMvc mvc;
    @Autowired MenuService menuService;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired CacheManager cacheManager;
    @Autowired TransactionTemplate tx;
    @Autowired com.noq.repository.CustomerOrderRepository orderRepository;

    private static final org.springframework.test.web.servlet.request.RequestPostProcessor KITCHEN = user("kitchen").roles("KITCHEN");
    private static final org.springframework.test.web.servlet.request.RequestPostProcessor STAFF = user("staff").roles("STAFF");

    private org.springframework.test.web.servlet.ResultActions setAvailable(long id, String body,
            org.springframework.test.web.servlet.request.RequestPostProcessor who) throws Exception {
        var request = patch("/api/menu/" + id + "/availability").contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(who == null ? request : request.with(who));
    }

    @Test
    void publicMenuShowsActiveItemsOnly() throws Exception {
        mvc.perform(get("/api/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Masala Dosa")))
                .andExpect(jsonPath("$[*].name", not(hasItem("Chilli Paneer")))) // inactive in data.sql
                .andExpect(jsonPath("$[0].version").doesNotExist());               // DTO, not the entity
    }

    @Test
    void menuCanBeFilteredByCategory() throws Exception {
        mvc.perform(get("/api/menu").param("category", "BEVERAGES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].category", everyItem(is("BEVERAGES"))));
        mvc.perform(get("/api/menu").param("category", "PIZZAS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void kitchenToggleAvailabilityIsSavedAndEnforcedByCheckout() throws Exception {
        long id = 20; // Chocolate Donut
        int stock = menuItemRepository.findById(id).orElseThrow().getAvailableQuantity();
        String order = "{\"items\":[{\"menuItemId\":" + id + ",\"quantity\":1}]}";

        setAvailable(id, "{\"available\":false}", KITCHEN).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        setAvailable(id, "{}", KITCHEN).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.available").exists());
        setAvailable(id, "{\"available\":true}", STAFF).andExpect(status().isForbidden());  // counter staff can't
        setAvailable(id, "{\"available\":true}", null).andExpect(status().isUnauthorized()); // customers can't
        mvc.perform(get("/api/menu/" + id)) // saved in the database, and the customer menu sees it
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.orderable").value(false))
                .andExpect(jsonPath("$.availableQuantity").value(stock));
        mvc.perform(get("/")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("Not available")));

        long ordersBefore = orderRepository.count();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(order)) // direct API call
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Chocolate Donut is not available right now"));
        assertThat(orderRepository.count()).as("no order saved").isEqualTo(ordersBefore);
        assertThat(menuItemRepository.findById(id).orElseThrow().getAvailableQuantity()).as("no stock taken").isEqualTo(stock);

        setAvailable(id, "{\"available\":true}", KITCHEN).andExpect(status().isOk());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(order)).andExpect(status().isCreated());
    }

    @Test
    void kitchenPageHasExactlyTwoTabs() throws Exception {
        mvc.perform(get("/staff/kitchen").with(KITCHEN))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(allOf(
                        containsString(">Menu Availability<"), containsString(">Incoming Orders<"),
                        containsString("data-availability"), containsString("data-mode=\"kitchen\""))));
        mvc.perform(get("/staff/kitchen").with(STAFF)).andExpect(status().isForbidden());
    }

    @Test
    void soldOutItemIsNotOrderable() throws Exception {
        mvc.perform(get("/api/menu/16")) // Margherita Pizza: stock 0
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderable").value(false));
        mvc.perform(get("/api/menu/9999")).andExpect(status().isNotFound());
    }

    @Test
    void hqlPopularQueryReturnsOnlyOrderableItemsByPopularity() {
        var popular = menuItemRepository.findPopularAvailable(Limit.of(50));
        assertThat(popular).allMatch(m -> m.isOrderable());
        for (int i = 1; i < popular.size(); i++) {
            assertThat(popular.get(i - 1).getPopularityCount()).isGreaterThanOrEqualTo(popular.get(i).getPopularityCount());
        }
    }

    @Test
    void realLoginsHaveExactlyTheirOwnRole() throws Exception {
        // real BCrypt logins against the seeded staff_user table: kitchen / kitchen123 (KITCHEN), staff / staff123 (STAFF)
        setAvailable(2, "{\"available\":true}", httpBasic("kitchen", "kitchen123")).andExpect(status().isOk());
        mvc.perform(post("/api/orders/NQ-2026-AAAAA/payments/counter-confirmation").with(httpBasic("kitchen", "kitchen123")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orders").param("status", "READY_FOR_PICKUP").with(httpBasic("staff", "staff123"))).andExpect(status().isOk());
        setAvailable(2, "{\"available\":true}", httpBasic("staff", "staff123")).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders").with(httpBasic("staff", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").with(httpBasic("admin", "admin123"))).andExpect(status().isUnauthorized()); // no admin any more
        // the menu can no longer be created, edited or deleted through the API
        mvc.perform(post("/api/menu").with(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"category\":\"SNACKS\",\"price\":10}")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/menu/2").with(KITCHEN)).andExpect(status().isForbidden());
    }

    @Test
    void staffLoginLandsOnTheirOwnPage() throws Exception {
        mvc.perform(get("/staff").with(KITCHEN)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/staff/kitchen"));
        mvc.perform(get("/staff").with(STAFF)).andExpect(status().isOk());
        mvc.perform(get("/staff/menu").with(STAFF)).andExpect(status().isNotFound()); // the admin menu page is gone
    }

    @Test
    void cacheIsFilledOnReadAndEvictedOnKitchenUpdate() throws Exception {
        var cache = cacheManager.getCache(MenuService.MENU_CACHE);
        cache.clear();
        menuService.getMenu(null);
        assertThat(cache.get("ALL")).as("first call stores the menu").isNotNull();

        setAvailable(7, "{\"available\":true}", KITCHEN).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
        assertThat(cache.get("ALL")).as("kitchen update evicts the cache").isNull();
    }

    @Test
    void cacheIsEvictedOnlyAfterCommitAndNotOnRollback() {
        var cache = cacheManager.getCache(MenuService.MENU_CACHE);
        menuService.getMenu(null);
        tx.executeWithoutResult(t -> {
            menuService.updateAvailability(19L, false);
            assertThat(cache.get("ALL")).as("still cached until the commit").isNotNull();
        });
        assertThat(cache.get("ALL")).as("evicted after the commit").isNull();

        menuService.getMenu(null);
        tx.executeWithoutResult(t -> {
            menuService.updateAvailability(19L, true);
            t.setRollbackOnly();
        });
        assertThat(cache.get("ALL")).as("a rollback keeps the (still correct) cache").isNotNull();
        assertThat(menuItemRepository.findById(19L).orElseThrow().isAvailable()).isFalse();
        menuService.updateAvailability(19L, true); // put the dish back on sale for other tests
    }
}
