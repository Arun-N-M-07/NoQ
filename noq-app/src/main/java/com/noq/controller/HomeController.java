package com.noq.controller;

import com.noq.enums.Category;
import com.noq.service.MenuService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Customer pages (Spring MVC + Thymeleaf): the menu and the cart. The cart page also shows the order
 * confirmation after checkout (rendered by cart.js from the POST /api/orders response).
 * Flow: browser -> DispatcherServlet -> this @Controller -> MenuService (cache/DB) -> Model -> Thymeleaf template -> HTML.
 */
@Controller
public class HomeController {

    private final MenuService menuService;

    public HomeController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("menu", menuService.getMenu(null));
        model.addAttribute("categories", Category.values());
        return "index"; // -> templates/index.html
    }

    @GetMapping("/cart")
    public String cart() {
        return "cart";
    }
}
