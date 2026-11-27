package ru.study.rates.web;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.study.rates.repository.RateRepository;

/** Измерительные эндпоинты доступны только в явно включённом профиле cache-lab. */
@RestController
@Profile("cache-lab")
public class CacheLabController {
    private final RateRepository repository;
    private final CacheManager manager;
    private final String instance;

    public CacheLabController(RateRepository repository, CacheManager manager,
                              @Value("${lab.instance:local}") String instance) {
        this.repository = repository;
        this.manager = manager;
        this.instance = instance;
    }

    @GetMapping("/api/lab/cache")
    public Map<String, Object> statistics() {
        return Map.of("instance", instance, "cacheManager", manager.getClass().getSimpleName(),
                "databaseReads", repository.readCount());
    }

    @DeleteMapping("/api/lab/cache")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @CacheEvict(cacheNames = "rates", allEntries = true)
    public void clear() { }
}
