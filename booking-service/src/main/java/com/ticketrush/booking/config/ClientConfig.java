package com.ticketrush.booking.config;

import com.ticketrush.common.security.ServiceTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
class ClientConfig {

    /** This service's identity for /internal/** calls (role SERVICE, 5 min, cached). */
    @Bean
    ServiceTokens.Provider serviceTokens(@Value("${ticketrush.internal-jwt-secret}") String secret, Clock clock) {
        return ServiceTokens.provider(secret, "booking-service", clock);
    }

    /**
     * Plain builder for every unqualified injection point. Eureka's own RestClient transport injects a
     * RestClient.Builder; if the only one were load-balanced, it would try to resolve "discovery-server"
     * through Eureka and the service would never register. Prototype, like Boot's default builder.
     */
    @Bean
    @Primary
    @Scope("prototype")
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /** connect 500 ms / read 2 s (spec §14): event-service must answer well inside the user's request. */
    @Bean
    RestClient eventServiceRestClient(@LoadBalanced RestClient.Builder builder,
                                      @Value("${ticketrush.event-service.base-url}") String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
