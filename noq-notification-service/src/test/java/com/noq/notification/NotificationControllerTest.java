package com.noq.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void acceptsValidOrderReadyEvent() throws Exception {
        mvc.perform(post("/api/notifications/order-ready").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderReference\":\"NQ-2026-AB12C\",\"eventType\":\"ORDER_READY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.orderReference").value("NQ-2026-AB12C"));

        mvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderReference").value("NQ-2026-AB12C"));
    }

    @Test
    void rejectsInvalidPayload() throws Exception {
        mvc.perform(post("/api/notifications/order-ready").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderReference\":\"bad\",\"eventType\":\"SOMETHING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.orderReference").exists())
                .andExpect(jsonPath("$.fieldErrors.eventType").exists());
    }
}
