package ru.study.rates.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CurrencyRate(LocalDate date, Currency currency, String name,
                           LocalDate effectiveDate, LocalDate previousEffectiveDate,
                           BigDecimal rate, BigDecimal previousRate,
                           BigDecimal change, BigDecimal changePercent, Instant fetchedAt) { }
