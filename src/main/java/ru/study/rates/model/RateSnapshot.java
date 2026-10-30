package ru.study.rates.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record RateSnapshot(LocalDate effectiveDate, Map<Currency, Quote> quotes) {
    public RateSnapshot {
        quotes = Map.copyOf(quotes);
    }

    public record Quote(String name, BigDecimal rublesPerUnit) { }
}
