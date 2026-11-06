package com.ticketrush.payment.config;

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
        return ServiceTokens.provider(secret, "payment-service", clock);
    }

    /**
     * Plain builder for every unqualified injection point (Eureka's own transport, the provider client).
     * If the only builder were load-balanced, Eureka would try to resolve "discovery-server" through itself
     * and the service would never register (found in plan 3). Prototype, like Boot's default builder.
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

    /** connect 500 ms / read 1 s (spec §14): validating the booking must be quick. */
    @Bean
    RestClient bookingRestClient(@LoadBalanced RestClient.Builder builder,
                                 @Value("${ticketrush.booking-service.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).requestFactory(factory(Duration.ofSeconds(1))).build();
    }

    /** External PSP, not in Eureka: plain client. connect 500 ms / read 3 s, never retried (spec §8.2). */
    @Bean
    RestClient providerRestClient(RestClient.Builder builder, @Value("${ticketrush.provider.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).requestFactory(factory(Duration.ofSeconds(3))).build();
    }

    private static JdkClientHttpRequestFactory factory(Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
