package com.ticketrush.event.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression: the only RestClient.Builder bean used to be @LoadBalanced, so Eureka's own RestClient
 * transport picked it up and tried to resolve "discovery-server" through Eureka -> the service never registered.
 */
class ClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoadBalancerAutoConfiguration.class))
            .withUserConfiguration(ClientConfig.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(LoadBalancerClient.class, () -> mock(LoadBalancerClient.class))
            .withPropertyValues(
                    "ticketrush.internal-jwt-secret=0123456789abcdef0123456789abcdef",
                    "ticketrush.booking-service.base-url=http://booking-service");

    @Test
    void unqualifiedRestClientBuilderIsNotLoadBalanced() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(interceptors(ctx.getBean(RestClient.Builder.class))).noneMatch(ClientConfigTest::isLoadBalancer);
        });
    }

    @Test
    void loadBalancedBuilderStillCarriesTheInterceptor() {
        runner.run(ctx -> {
            var lb = ctx.getBeanProvider(RestClient.Builder.class)
                    .stream()
                    .filter(b -> interceptors(b).stream().anyMatch(ClientConfigTest::isLoadBalancer))
                    .toList();
            assertThat(lb).hasSize(1);
        });
    }

    private static boolean isLoadBalancer(ClientHttpRequestInterceptor i) {
        return i instanceof LoadBalancerInterceptor || i instanceof DeferringLoadBalancerInterceptor;
    }

    private static List<ClientHttpRequestInterceptor> interceptors(RestClient.Builder builder) {
        List<ClientHttpRequestInterceptor> out = new ArrayList<>();
        builder.clone().requestInterceptors(out::addAll);
        return out;
    }

}
