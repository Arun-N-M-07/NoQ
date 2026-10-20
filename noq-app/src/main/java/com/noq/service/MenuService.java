package com.noq.service;

import com.noq.dto.AvailabilityRequest;
import com.noq.dto.MenuItemRequest;
import com.noq.dto.MenuItemResponse;
import com.noq.entity.MenuItem;
import com.noq.enums.Category;
import com.noq.exception.ApiException;
import com.noq.repository.MenuItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Menu business logic + CACHING demo.
 * <p>
 * {@code @Cacheable}: the first call runs the method and stores the result in the Caffeine cache;
 * later calls with the same key return the stored list WITHOUT running the method (no SQL query).
 * {@link #evictMenuCaches()}: after the menu, stock or popularity changes, the cached lists are thrown away
 * AFTER the transaction commits, so the next read loads fresh data. Checkout never uses these cached lists -
 * it always reads stock from the database.
 */
@Service
public class MenuService {

    public static final String MENU_CACHE = "menu";
    public static final String POPULAR_CACHE = "popularItems";
    private static final int MAX_STOCK = 10000;

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    private final MenuItemRepository menuItemRepository;
    private final CacheManager cacheManager;

    // Constructor injection: Spring passes in the beans it created.
    public MenuService(MenuItemRepository menuItemRepository, CacheManager cacheManager) {
        this.menuItemRepository = menuItemRepository;
        this.cacheManager = cacheManager;
    }

    @Cacheable(cacheNames = MENU_CACHE, key = "#category == null ? 'ALL' : #category.name()")
    @Transactional(readOnly = true)
    public List<MenuItemResponse> getMenu(Category category) {
        log.info("Cache MISS: loading menu ({}) from the database", category == null ? "ALL" : category);
        List<MenuItem> items = category == null
                ? menuItemRepository.findAllActive()
                : menuItemRepository.findActiveByCategory(category);
        return items.stream().map(MenuItemResponse::from).toList();
    }

    @Cacheable(cacheNames = POPULAR_CACHE, key = "#limit")
    @Transactional(readOnly = true)
    public List<MenuItemResponse> getPopularItems(int limit) {
        log.info("Cache MISS: loading top {} popular items from the database", limit);
        return menuItemRepository.findPopularAvailable(Limit.of(limit)).stream().map(MenuItemResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MenuItemResponse getItem(Long id) {
        return MenuItemResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public List<MenuItemResponse> listAllForStaff() {
        return menuItemRepository.findAllByOrderByActiveDescCategoryAscNameAsc().stream().map(MenuItemResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MenuItemRequest getEditForm(Long id) {
        return MenuItemRequest.from(find(id));
    }

    @Transactional
    public MenuItemResponse create(MenuItemRequest request) {
        MenuItem item = new MenuItem();
        request.applyTo(item);
        item.setAvailableQuantity(request.getAvailableQuantity()); // starting stock of a NEW item
        MenuItemResponse saved = MenuItemResponse.from(menuItemRepository.save(item));
        evictMenuCaches();
        return saved;
    }

    @Transactional
    public MenuItemResponse update(Long id, MenuItemRequest request) {
        MenuItem item = find(id);
        request.applyTo(item); // managed entity: Hibernate writes the UPDATE automatically at commit ("dirty checking")
        evictMenuCaches();
        return MenuItemResponse.from(item);
    }

    @Transactional
    public MenuItemResponse updateAvailability(Long id, AvailabilityRequest request) {
        Integer newStock = request.availableQuantity();
        Boolean newAvailable = request.available();
        if (newStock == null && newAvailable == null) {
            throw ApiException.badRequest("Send availableQuantity and/or available");
        }
        if (newStock != null && (newStock < 0 || newStock > MAX_STOCK)) {
            throw ApiException.badRequest("Stock must be between 0 and " + MAX_STOCK);
        }

        MenuItem item = find(id);
        if (newStock != null) {
            item.setAvailableQuantity(newStock);
        }
        if (newAvailable != null) {
            item.setAvailable(newAvailable);
        }
        evictMenuCaches();
        return MenuItemResponse.from(item);
    }

    /** Soft delete / restore. Old orders keep pointing at the row, so history is never lost. */
    @Transactional
    public MenuItemResponse setActive(Long id, boolean active) {
        MenuItem item = find(id);
        item.setActive(active);
        evictMenuCaches();
        return MenuItemResponse.from(item);
    }

    /**
     * Throws the cached menu lists away once the current transaction has COMMITTED (immediately if there is none).
     * Evicting earlier would let another request re-cache the old rows before our commit; a rollback evicts nothing.
     * Also called by OrderService after orders change stock or popularity.
     */
    public void evictMenuCaches() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearMenuCaches();
                }
            });
        } else {
            clearMenuCaches();
        }
    }

    private void clearMenuCaches() {
        cacheManager.getCache(MENU_CACHE).clear();
        cacheManager.getCache(POPULAR_CACHE).clear();
        log.info("Menu caches evicted");
    }

    private MenuItem find(Long id) {
        return menuItemRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Menu item " + id + " not found"));
    }
}
