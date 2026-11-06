package com.ticketrush.event.config;

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

/** event-service's first outbound client: the seat reconciler asks booking-service for booking states (spec §12). */
@Configuration
class ClientConfig {

    /** This service's identity for /internal/** calls (role SERVICE, 5 min, cached). */
    @Bean
    ServiceTokens.Provider serviceTokens(@Value("${ticketrush.internal-jwt-secret}") String secret, Clock clock) {
        return ServiceTokens.provider(secret, "event-service", clock);
    }

    /**
     * Plain builder for every unqualified injection point. If the only builder were load-balanced, Eureka's own
     * transport would try to resolve "discovery-server" through itself and never register (found in plan 3).
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

    /** connect 500 ms / read 2 s (spec §14, "reconcilers -> booking status"). */
    @Bean
    RestClient bookingRestClient(@LoadBalanced RestClient.Builder builder,
                                 @Value("${ticketrush.booking-service.base-url}") String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
