package com.noq.restcontroller;

import com.noq.service.ConceptDemoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** STAFF-only endpoints used during the viva to show framework internals. */
@RestController
@RequestMapping("/api/demo")
public class DemoRestController {

    private final ConceptDemoService demoService;

    public DemoRestController(ConceptDemoService demoService) {
        this.demoService = demoService;
    }

    @GetMapping("/hibernate-session")
    public Map<String, Object> hibernateSession() {
        return demoService.hibernateSessionDemo();
    }

    @GetMapping("/spring-container")
    public Map<String, Object> springContainer() {
        return demoService.springContainerDemo();
    }
}
