package com.paycore.history.config;

import com.paycore.history.error.JsonAccessDeniedHandler;
import com.paycore.history.error.JsonAuthenticationEntryPoint;
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
 * history-service verifies AuthCore's JWTs itself and never authorizes on GateKeeper's
 * {@code X-GK-*} headers: calling :8084 directly is exactly as secure as calling it through
 * the gateway.
 *
 * <p>All authorization is here, not in {@code @PreAuthorize}, so every check completes in the
 * filter chain before any handler runs. Deny by default: a path with no rule is refused even
 * with a full token.
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
                // Permitting ERROR dispatches keeps Boot's error rendering from being
                // re-authorized. Not every ERROR dispatch follows a request that already
                // passed this chain — a JWKS outage or a firewall rejection also end in one —
                // so re-running authorization here would disguise a 500 as a 401 instead of
                // letting an AuthCore outage surface as the 500 it actually is.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/payments/*/history")
                    .access(PaymentsAuthorization.requires("payments:read"))
                // Deny by default, but say so: an unmapped route is not the caller's
                // permissions being wrong, it is that the operation does not exist.
                .anyRequest().access((authentication, context) -> new DetailedDenial("no such operation")))
            .oauth2ResourceServer(resourceServer -> resourceServer
                .accessDeniedHandler(accessDeniedHandler)
                .authenticationEntryPoint(authenticationEntryPoint)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            // Deliberately duplicates the handlers set on oauth2ResourceServer above, so refusals from both AuthorizationFilter and ExceptionTranslationFilter keep the platform shape even if the resource-server wiring changes.
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
