package com.noq;

import com.noq.dto.AvailabilityRequest;
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

/** Menu REST API, validation, authorization, HQL query 1 and caching. Runs on the in-memory H2 profile. */
@SpringBootTest(properties = {"noq.notification.base-url=http://localhost:1", "noq.orders.expiry-check-interval=PT24H"})
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class MenuApiTest {

    @Autowired MockMvc mvc;
    @Autowired MenuService menuService;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired CacheManager cacheManager;
    @Autowired TransactionTemplate tx;

    private static final String NEW_ITEM = """
            {"name":"Test Vada Pav","description":"Test item","category":"SNACKS","price":25.50,"availableQuantity":10}""";

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
    void menuChangesNeedAdmin() throws Exception {
        mvc.perform(post("/api/menu").contentType(MediaType.APPLICATION_JSON).content(NEW_ITEM))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/menu").with(user("kitchen").roles("STAFF")).contentType(MediaType.APPLICATION_JSON).content(NEW_ITEM))
                .andExpect(status().isForbidden());
        // real BCrypt login against the seeded staff_user table
        mvc.perform(post("/api/menu").with(httpBasic("admin", "admin123")).contentType(MediaType.APPLICATION_JSON).content(NEW_ITEM))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Vada Pav"));
        mvc.perform(post("/api/menu").with(httpBasic("admin", "wrong")).contentType(MediaType.APPLICATION_JSON).content(NEW_ITEM))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidMenuItemIsRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/menu").with(user("admin").roles("ADMIN", "STAFF")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"price\":-5,\"availableQuantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.price").exists())
                .andExpect(jsonPath("$.fieldErrors.category").exists())
                .andExpect(jsonPath("$.fieldErrors.availableQuantity").exists());
    }

    @Test
    void cacheIsFilledOnReadAndEvictedOnStaffUpdate() throws Exception {
        var cache = cacheManager.getCache(MenuService.MENU_CACHE);
        cache.clear();
        menuService.getMenu(null);
        assertThat(cache.get("ALL")).as("first call stores the menu").isNotNull();

        mvc.perform(patch("/api/menu/7/availability").with(user("admin").roles("ADMIN", "STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"availableQuantity\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(100));
        assertThat(cache.get("ALL")).as("staff update evicts the cache").isNull();
    }

    @Test
    void cacheIsEvictedOnlyAfterCommitAndNotOnRollback() {
        var cache = cacheManager.getCache(MenuService.MENU_CACHE);
        menuService.getMenu(null);
        tx.executeWithoutResult(t -> {
            menuService.updateAvailability(19L, new AvailabilityRequest(77, null));
            assertThat(cache.get("ALL")).as("still cached until the commit").isNotNull();
        });
        assertThat(cache.get("ALL")).as("evicted after the commit").isNull();

        menuService.getMenu(null);
        tx.executeWithoutResult(t -> {
            menuService.updateAvailability(19L, new AvailabilityRequest(1, null));
            t.setRollbackOnly();
        });
        assertThat(cache.get("ALL")).as("a rollback keeps the (still correct) cache").isNotNull();
        assertThat(menuItemRepository.findById(19L).orElseThrow().getAvailableQuantity()).isEqualTo(77);
    }

    @Test
    void staleMenuEditCannotRestoreSoldStock() throws Exception {
        var admin = user("admin").roles("ADMIN", "STAFF");
        String created = mvc.perform(post("/api/menu").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Stale Brownie\",\"category\":\"DESSERTS\",\"price\":30,\"availableQuantity\":10}"))
                .andReturn().getResponse().getContentAsString();
        int id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        // staff opens the edit form (stock 10); meanwhile a customer buys 3
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"menuItemId\":" + id + ",\"quantity\":3}],\"paymentMethod\":\"PAY_AT_COUNTER\"}"))
                .andExpect(status().isCreated());
        // the stale form is saved: new price, old stock 10
        mvc.perform(put("/api/menu/" + id).with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stale Brownie\",\"category\":\"DESSERTS\",\"price\":35,\"availableQuantity\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(35))
                .andExpect(jsonPath("$.availableQuantity").value(7));
    }

    @Test
    void deleteDeactivatesInsteadOfDeleting() throws Exception {
        String body = mvc.perform(post("/api/menu").with(user("admin").roles("ADMIN", "STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_ITEM))
                .andReturn().getResponse().getContentAsString();
        Integer id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        mvc.perform(delete("/api/menu/" + id).with(user("admin").roles("ADMIN", "STAFF")))
                .andExpect(status().isNoContent());
        assertThat(menuItemRepository.findById(id.longValue())).get().matches(m -> !m.isActive());
    }
}
