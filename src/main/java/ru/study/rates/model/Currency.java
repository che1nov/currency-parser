package ru.study.rates.model;

import java.util.Locale;

public enum Currency {
    USD, EUR, CNY, GBP, JPY;

    public static Currency parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Поддерживаются только USD, EUR, CNY, GBP, JPY");
        }
    }
}
