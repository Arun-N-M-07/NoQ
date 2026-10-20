package com.noq.controller;

import com.noq.dto.MenuItemResponse;
import com.noq.enums.Category;
import com.noq.service.MenuService;
import com.noq.service.OrderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalTime;
import java.util.List;

/**
 * Customer pages (Spring MVC + Thymeleaf).
 * Flow: browser -> DispatcherServlet -> this @Controller -> MenuService (cache/DB) -> Model -> Thymeleaf template -> HTML.
 */
@Controller
public class HomeController {

    private final MenuService menuService;
    private final OrderService orderService;
    private final LocalTime opensAt;
    private final LocalTime closesAt;

    public HomeController(MenuService menuService, OrderService orderService,
                          @Value("${noq.canteen.opens-at:08:00}") String opensAt,
                          @Value("${noq.canteen.closes-at:18:00}") String closesAt) {
        this.menuService = menuService;
        this.orderService = orderService;
        this.opensAt = LocalTime.parse(opensAt);
        this.closesAt = LocalTime.parse(closesAt);
    }

    @GetMapping("/")
    public String index(Model model) {
        List<MenuItemResponse> popular = menuService.getPopularItems(6);
        LocalTime now = LocalTime.now();
        model.addAttribute("menu", menuService.getMenu(null));
        model.addAttribute("popular", popular);
        model.addAttribute("popularIsFromOrders", popular.stream().anyMatch(i -> i.popularityCount() > 0));
        model.addAttribute("categories", Category.values());
        model.addAttribute("canteenOpen", !now.isBefore(opensAt) && now.isBefore(closesAt));
        model.addAttribute("opensAt", opensAt);
        model.addAttribute("closesAt", closesAt);
        return "index"; // -> templates/index.html
    }

    @GetMapping("/cart")
    public String cart() {
        return "cart";
    }

    @GetMapping("/track")
    public String track() {
        return "track";
    }

    /** SIMULATED online payment page. Amount comes from the server, not the browser. */
    @GetMapping("/pay/{orderReference}")
    public String pay(@PathVariable String orderReference, Model model) {
        model.addAttribute("order", orderService.getStatus(orderReference));
        return "pay";
    }
}
