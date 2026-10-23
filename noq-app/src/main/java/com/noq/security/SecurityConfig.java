package com.noq.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noq.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;

/**
 * Who may call what. Authorization is enforced HERE on the server - hiding a button in the
 * page is not security.
 * <ul>
 *   <li>Customers (anonymous): menu, place order, and read their own order back with ref + OTP.</li>
 *   <li>KITCHEN: Kitchen page - dish availability, incoming orders, "Ready for Pickup".</li>
 *   <li>STAFF: Staff page - ready orders, counter payment, pickup OTP check and handover.</li>
 * </ul>
 * Browser pages use a login form (session cookie); Postman uses HTTP Basic authentication.
 */
@Configuration
public class SecurityConfig {

    private static final RequestMatcher API = request ->
            request.getRequestURI().startsWith("/api/") || request.getRequestURI().startsWith("/actuator/");

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        // public pages and static files
                        .requestMatchers("/", "/cart", "/staff/login", "/error",
                                "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        // pages: /staff sends each user to their own page (StaffController)
                        .requestMatchers("/staff/kitchen").hasRole("KITCHEN")
                        .requestMatchers("/staff").hasAnyRole("KITCHEN", "STAFF")
                        // menu API: anyone can read; the kitchen switches availability
                        .requestMatchers(HttpMethod.GET, "/api/menu/**").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/menu/*/availability").hasRole("KITCHEN")
                        // order API: customer endpoints (reading an order checks the OTP inside the service)
                        .requestMatchers(HttpMethod.POST, "/api/orders").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/orders/*").permitAll() // one order (not the list)
                        .requestMatchers(HttpMethod.GET, "/api/orders").hasAnyRole("KITCHEN", "STAFF") // each page's list
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/status").hasRole("KITCHEN") // Ready for Pickup
                        // everything else under /api (counter payment, OTP check, handover, demos) + actuator
                        .requestMatchers("/api/**", "/actuator/**").hasRole("STAFF")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/staff/login")
                        .defaultSuccessUrl("/staff", true)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/staff/logout")
                        .logoutSuccessUrl("/staff/login?logout"))
                .httpBasic(basic -> basic.authenticationEntryPoint(jsonError(HttpStatus.UNAUTHORIZED)))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(jsonError(HttpStatus.UNAUTHORIZED), API)
                        .defaultAccessDeniedHandlerFor(jsonDenied(), API))
                // CSRF tokens protect the HTML forms. The JSON API is called by our own JavaScript and by Postman;
                // cross-site form posts cannot send JSON, and the session cookie is SameSite=Lax (application.properties).
                .csrf(csrf -> csrf.ignoringRequestMatchers(API));
        return http.build();
    }

    /** 401 as JSON (no WWW-Authenticate header, so browsers do not pop up a login box). */
    private AuthenticationEntryPoint jsonError(HttpStatus status) {
        return (request, response, ex) -> write(request, response, status, "Staff login required");
    }

    private AccessDeniedHandler jsonDenied() {
        return (request, response, ex) -> write(request, response, HttpStatus.FORBIDDEN, "You do not have permission for this action");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }
}
