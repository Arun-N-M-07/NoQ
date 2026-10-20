package com.noq.exception;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** For HTML pages: business errors are shown on a friendly error page instead of JSON. */
@ControllerAdvice(basePackages = "com.noq.controller")
public class PageExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public String handle(ApiException ex, Model model, HttpServletResponse response) {
        response.setStatus(ex.getStatus().value());
        model.addAttribute("status", ex.getStatus().value());
        model.addAttribute("message", ex.getMessage());
        return "error";
    }
}
