package com.noq.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Calls the separate noq-notification-service (port 8081) over HTTP using Spring's RestClient.
 * <p>
 * It runs only AFTER the "order is READY_FOR_PICKUP" transaction has committed, so the order status is already
 * saved even if the notification service is down. Failures are logged, never thrown back to the staff user.
 * Limitation: this is a synchronous call with no retry - a message broker (RabbitMQ/Kafka) would make it
 * reliable, but that is deliberately out of scope for this project.
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestClient restClient;

    public NotificationClient(RestClient.Builder builder, @Value("${noq.notification.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(2000); // do not let a dead service slow the dashboard down
        timeouts.setReadTimeout(2000);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(timeouts).build();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderReady(OrderReadyEvent event) {
        try {
            Map<?, ?> ack = restClient.post()
                    .uri("/api/notifications/order-ready")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderReference", event.orderReference(), "eventType", "ORDER_READY"))
                    .retrieve()
                    .body(Map.class);
            log.info("Notification service acknowledged order {} (status {})", event.orderReference(),
                    ack == null ? "?" : ack.get("status"));
        } catch (RestClientException e) {
            log.warn("Notification service unavailable; order {} is still READY_FOR_PICKUP. Reason: {}",
                    event.orderReference(), e.getMessage());
        }
    }
}
