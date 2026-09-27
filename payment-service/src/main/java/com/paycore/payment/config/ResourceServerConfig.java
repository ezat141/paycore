package com.paycore.payment.config;

import com.paycore.payment.error.JsonAccessDeniedHandler;
import com.paycore.payment.error.JsonAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * payment-service verifies AuthCore's JWTs itself and never authorizes on GateKeeper's
 * {@code X-GK-*} headers: calling :8083 directly is exactly as secure as calling it through
 * the gateway.
 *
 * <p>All authorization is here, not in {@code @PreAuthorize}. Spring MVC resolves and
 * validates a {@code @Valid @RequestBody} before a method-security interceptor runs, so with
 * annotations an unauthorized caller could get a 400 and learn the validation rules. Here,
 * every check completes before the body is read. Deny by default: a path with no rule is
 * refused even with a full token.
 */
@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JsonAccessDeniedHandler accessDeniedHandler,
                                            JsonAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                // Boot's error page is rendered on an ERROR dispatch of the original request,
                // which already passed this chain; refusing it would turn a 500 into a 403.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/payments", "/payments/*")
                    .access(PaymentsAuthorization.requires("payments:read"))
                .requestMatchers(HttpMethod.POST, "/payments", "/payments/*/capture", "/payments/*/void")
                    .access(PaymentsAuthorization.requires("payments:write"))
                .anyRequest().denyAll())
            .oauth2ResourceServer(resourceServer -> resourceServer
                .accessDeniedHandler(accessDeniedHandler)
                .authenticationEntryPoint(authenticationEntryPoint)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .exceptionHandling(handling -> handling
                .accessDeniedHandler(accessDeniedHandler)
                .authenticationEntryPoint(authenticationEntryPoint))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new AuthCoreAuthoritiesConverter());
        return converter;
    }
}
