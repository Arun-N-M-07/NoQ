package com.noq.restcontroller;

import com.noq.dto.AvailabilityRequest;
import com.noq.dto.MenuItemResponse;
import com.noq.enums.Category;
import com.noq.service.MenuService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** JSON API for the menu. GET is public; the availability switch needs a KITCHEN login (see SecurityConfig). */
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

    /** KITCHEN: {"available": false} or {"available": true}. */
    @PatchMapping("/{id}/availability")
    public MenuItemResponse updateAvailability(@PathVariable Long id, @Valid @RequestBody AvailabilityRequest request) {
        return menuService.updateAvailability(id, request.available());
    }
}
