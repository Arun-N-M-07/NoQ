package com.noq.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Staff login, the Staff page and the Kitchen page (both load their data from the REST API with JavaScript). */
@Controller
@RequestMapping("/staff")
public class StaffController {

    @GetMapping("/login")
    public String login() {
        return "staff/login";
    }

    /** Where every login lands: the kitchen goes to its own page, counter staff see the ready orders. */
    @GetMapping
    public String dashboard(Authentication authentication) {
        boolean kitchen = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_KITCHEN".equals(a.getAuthority()));
        return kitchen ? "redirect:/staff/kitchen" : "staff/dashboard";
    }

    /** Two tabs: Menu Availability (toggle each dish) and Incoming Orders (with "Ready for Pickup"). */
    @GetMapping("/kitchen")
    public String kitchen() {
        return "staff/kitchen";
    }
}
