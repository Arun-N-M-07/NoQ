package com.noq.restcontroller;

import com.noq.dto.AvailabilityRequest;
import com.noq.dto.MenuItemRequest;
import com.noq.dto.MenuItemResponse;
import com.noq.enums.Category;
import com.noq.service.MenuService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/** JSON API for the menu. GET is public; POST/PUT/PATCH/DELETE need an ADMIN login (see SecurityConfig). */
@RestController
@RequestMapping("/api/menu")
public class MenuRestController {

    private final MenuService menuService;

    public MenuRestController(MenuService menuService) {
        this.menuService = menuService;
    }

    /** GET /api/menu  or  GET /api/menu?category=SNACKS */
    @GetMapping
    public List<MenuItemResponse> getMenu(@RequestParam(required = false) Category category) {
        return menuService.getMenu(category);
    }

    /** GET /api/menu/popular?limit=6 */
    @GetMapping("/popular")
    public List<MenuItemResponse> getPopular(@RequestParam(defaultValue = "6") int limit) {
        return menuService.getPopularItems(Math.clamp(limit, 1, 20));
    }

    @GetMapping("/{id}")
    public MenuItemResponse getItem(@PathVariable Long id) {
        return menuService.getItem(id);
    }

    /** 201 Created + Location header pointing at the new item. */
    @PostMapping
    public ResponseEntity<MenuItemResponse> create(@Valid @RequestBody MenuItemRequest request) {
        MenuItemResponse created = menuService.create(request);
        return ResponseEntity.created(URI.create("/api/menu/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public MenuItemResponse update(@PathVariable Long id, @Valid @RequestBody MenuItemRequest request) {
        return menuService.update(id, request);
    }

    /** Partial update of stock and/or the sold-out switch. */
    @PatchMapping("/{id}/availability")
    public MenuItemResponse updateAvailability(@PathVariable Long id, @Valid @RequestBody AvailabilityRequest request) {
        return menuService.updateAvailability(id, request);
    }

    /** "Delete" = deactivate (soft delete), so past orders that reference the item stay valid. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        menuService.setActive(id, false);
        return ResponseEntity.noContent().build();
    }
}
