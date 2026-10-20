package com.noq.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Staff login page and the live order dashboard (the dashboard loads orders from /api/orders with JavaScript). */
@Controller
@RequestMapping("/staff")
public class StaffController {

    @GetMapping("/login")
    public String login() {
        return "staff/login";
    }

    @GetMapping
    public String dashboard() {
        return "staff/dashboard";
    }
}
