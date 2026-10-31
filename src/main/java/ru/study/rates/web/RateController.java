package ru.study.rates.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.study.rates.adapter.CbrClient;
import ru.study.rates.adapter.CollectionJournal;
import ru.study.rates.model.Currency;
import ru.study.rates.model.CurrencyRate;
import ru.study.rates.service.RateCollectionService;
import ru.study.rates.service.RateQueryService;

@RestController
public class RateController {
    private final RateCollectionService collector;
    private final RateQueryService query;
    private final CollectionJournal journal;

    public RateController(RateCollectionService collector, RateQueryService query, CollectionJournal journal) {
        this.collector = collector;
        this.query = query;
        this.journal = journal;
    }

    @PostMapping("/api/collect")
    public CollectionResponse collect(@RequestBody CollectRequest request) {
        LocalDate date = request.date() == null ? collector.today() : request.date();
        var rates = collector.collect(date, request.sourceUrl());
        return new CollectionResponse("SUCCEEDED", date, rates.size(), CbrClient.SOURCE_URL,
                rates.stream().map(RateResponse::from).toList());
    }

    @GetMapping("/answer")
    public List<RateResponse> answer(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "currency") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        var requestedDate = date == null ? collector.today() : date;
        var selectedCurrency = currency == null ? null : Currency.parse(currency);
        collector.validateDate(requestedDate);
        return query.find(requestedDate, selectedCurrency, sort, direction).stream()
                .map(RateResponse::from).toList();
    }

    @GetMapping("/api/status")
    public RateCollectionService.CollectionStatus status() {
        return collector.status();
    }

    @GetMapping("/api/journal")
    public CollectionJournal.Health journal() {
        return journal.health();
    }

    @GetMapping("/api/currencies")
    public List<String> currencies() {
        return Arrays.stream(Currency.values()).map(Enum::name).toList();
    }

    public record CollectRequest(LocalDate date, String sourceUrl) { }
    public record CollectionResponse(String state, LocalDate date, int savedCount,
                                     String sourceUrl, List<RateResponse> rates) { }
    public record RateResponse(LocalDate date, String currency, String name, String baseCurrency,
                               LocalDate effectiveDate, LocalDate previousEffectiveDate,
                               BigDecimal rate, BigDecimal previousRate, BigDecimal change,
                               BigDecimal changePercent, Instant fetchedAt) {
        static RateResponse from(CurrencyRate rate) {
            return new RateResponse(rate.date(), rate.currency().name(), rate.name(), "RUB",
                    rate.effectiveDate(), rate.previousEffectiveDate(), rate.rate(), rate.previousRate(),
                    rate.change(), rate.changePercent(), rate.fetchedAt());
        }
    }
}
