package ru.study.rates.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import ru.study.rates.model.CurrencyRate;

@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
    RedisCacheManagerBuilderCustomizer rateCacheSerialization(ObjectMapper mapper, CacheProperties properties) {
        // Конкретный тип без polymorphic default typing; BigDecimal и даты сохраняются как JSON.
        var type = mapper.getTypeFactory().constructCollectionType(List.class, CurrencyRate.class);
        var serializer = new Jackson2JsonRedisSerializer<List<CurrencyRate>>(mapper.copy(), type);
        var configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getRedis().getTimeToLive())
                .disableCachingNullValues()
                .prefixCacheNameWith(properties.getRedis().getKeyPrefix())
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
        return builder -> builder.withCacheConfiguration("rates", configuration);
    }
}
