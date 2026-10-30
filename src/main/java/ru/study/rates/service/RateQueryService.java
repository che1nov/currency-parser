package ru.study.rates.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import ru.study.rates.model.Currency;
import ru.study.rates.model.CurrencyRate;
import ru.study.rates.repository.RateRepository;

@Service
public class RateQueryService {
    private final RateRepository repository;

    public RateQueryService(RateRepository repository) {
        this.repository = repository;
    }

    public List<CurrencyRate> find(LocalDate date, Currency currency, String sort, String direction) {
        Comparator<CurrencyRate> comparator = switch (sort) {
            case "currency" -> Comparator.comparing(rate -> rate.currency().name());
            case "name" -> Comparator.comparing(CurrencyRate::name);
            case "rate" -> Comparator.comparing(CurrencyRate::rate);
            case "change" -> Comparator.comparing(CurrencyRate::change);
            case "changePercent" -> Comparator.comparing(CurrencyRate::changePercent);
            default -> throw new IllegalArgumentException("sort: currency, name, rate, change или changePercent");
        };
        if (!"asc".equals(direction) && !"desc".equals(direction)) {
            throw new IllegalArgumentException("direction: asc или desc");
        }
        if ("desc".equals(direction)) {
            comparator = comparator.reversed();
        }
        // JDBC работает в одном потоке; в parallelStream передаём отсоединённые immutable records.
        return repository.findByDate(date).parallelStream()
                .filter(rate -> currency == null || rate.currency() == currency)
                .sorted(comparator.thenComparing(rate -> rate.currency().name()))
                .toList();
    }
}
