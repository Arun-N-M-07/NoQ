package com.noq.controller;

import com.noq.dto.AvailabilityRequest;
import com.noq.dto.MenuItemRequest;
import com.noq.dto.MenuItemResponse;
import com.noq.enums.Category;
import com.noq.service.MenuService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Classic MVC form handling for menu management (ADMIN only):
 * GET shows a form, POST validates it with @Valid; on errors the same form is shown again with messages,
 * on success we redirect (Post/Redirect/Get) so refreshing the page does not resubmit.
 */
@Controller
@RequestMapping("/staff/menu")
public class StaffMenuController {

    private final MenuService menuService;

    public StaffMenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items", menuService.listAllForStaff());
        return "staff/menu";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        return showForm(model, new MenuItemRequest(), null);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") MenuItemRequest form, BindingResult errors,
                         Model model, RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            return showForm(model, form, null);
        }
        MenuItemResponse created = menuService.create(form);
        redirect.addFlashAttribute("message", "Added " + created.name());
        return "redirect:/staff/menu";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        return showForm(model, menuService.getEditForm(id), id);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") MenuItemRequest form, BindingResult errors,
                         Model model, RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            return showForm(model, form, id);
        }
        MenuItemResponse updated = menuService.update(id, form);
        redirect.addFlashAttribute("message", "Saved " + updated.name());
        return "redirect:/staff/menu";
    }

    /** Quick stock edit and sold-out switch from the menu table. */
    @PostMapping("/{id}/availability")
    public String availability(@PathVariable Long id,
                               @RequestParam(required = false) Integer availableQuantity,
                               @RequestParam(required = false) Boolean available,
                               RedirectAttributes redirect) {
        MenuItemResponse item = menuService.updateAvailability(id, new AvailabilityRequest(availableQuantity, available));
        redirect.addFlashAttribute("message", "Updated " + item.name());
        return "redirect:/staff/menu";
    }

    @PostMapping("/{id}/active")
    public String active(@PathVariable Long id, @RequestParam boolean active, RedirectAttributes redirect) {
        MenuItemResponse item = menuService.setActive(id, active);
        redirect.addFlashAttribute("message", (active ? "Reactivated " : "Deactivated ") + item.name());
        return "redirect:/staff/menu";
    }

    private String showForm(Model model, MenuItemRequest form, Long id) {
        model.addAttribute("form", form);
        model.addAttribute("itemId", id);
        model.addAttribute("categories", Category.values());
        return "staff/menu-form";
    }
}
