package com.ticketrush.event.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Catalog cache in Redis (spec §15). Keys are prefixed ev:cache: (event-service owns that keyspace).
 * Values use JDK serialisation of Serializable records. Redis errors never fail a request: the error
 * handler logs and the cached method simply runs against MySQL.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String EVENT = "ev-event";
    public static final String EVENTS = "ev-events";
    public static final String AVAILABLE = "ev-avail";

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    CacheManager cacheManager(RedisConnectionFactory redis) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("ev:cache:")
                .disableCachingNullValues();
        return RedisCacheManager.builder(redis)
                .withInitialCacheConfigurations(Map.of(
                        EVENT, base.entryTtl(Duration.ofMinutes(10)),
                        EVENTS, base.entryTtl(Duration.ofMinutes(10)),
                        AVAILABLE, base.entryTtl(Duration.ofSeconds(5))))
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(1)))
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache get failed ({} / {}), reading from DB: {}", cache.getName(), key, e.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache put failed ({} / {}): {}", cache.getName(), key, e.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache evict failed ({} / {}): {}", cache.getName(), key, e.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Cache clear failed ({}): {}", cache.getName(), e.toString());
            }
        };
    }
}
