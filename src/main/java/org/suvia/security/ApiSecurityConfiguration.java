package org.suvia.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(ApiSecurityProperties.class)
@Slf4j
public class ApiSecurityConfiguration {

    @Bean
    @ConditionalOnProperty(name = "suvia.security.enabled", havingValue = "true")
    SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> {}))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "suvia.security.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain localDevelopmentSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "suvia.security.enabled", havingValue = "false", matchIfMissing = true)
    InitializingBean insecureDevelopmentModeWarning() {
        return () -> log.warn(
                "SUVIA SECURITY IS DISABLED. This mode is for local development only; use the prod profile with JWT."
        );
    }
}
