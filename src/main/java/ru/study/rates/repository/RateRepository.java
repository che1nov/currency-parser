package ru.study.rates.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.study.rates.model.Currency;
import ru.study.rates.model.CurrencyRate;

@Repository
public class RateRepository {
    private final JdbcTemplate jdbc;

    public RateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveAll(List<CurrencyRate> rates) {
        jdbc.batchUpdate("""
                MERGE INTO currency_rate (requested_date, currency, name, effective_date,
                    previous_effective_date, rate, previous_rate, change_amount, change_percent, fetched_at)
                KEY (requested_date, currency) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rates, rates.size(), (statement, rate) -> {
            statement.setObject(1, rate.date());
            statement.setString(2, rate.currency().name());
            statement.setString(3, rate.name());
            statement.setObject(4, rate.effectiveDate());
            statement.setObject(5, rate.previousEffectiveDate());
            statement.setBigDecimal(6, rate.rate());
            statement.setBigDecimal(7, rate.previousRate());
            statement.setBigDecimal(8, rate.change());
            statement.setBigDecimal(9, rate.changePercent());
            statement.setObject(10, rate.fetchedAt().atOffset(ZoneOffset.UTC));
        });
    }

    public List<CurrencyRate> findByDate(LocalDate date) {
        return jdbc.query("SELECT * FROM currency_rate WHERE requested_date = ?", (rs, row) ->
                new CurrencyRate(rs.getObject("requested_date", LocalDate.class),
                        Currency.valueOf(rs.getString("currency")), rs.getString("name"),
                        rs.getObject("effective_date", LocalDate.class),
                        rs.getObject("previous_effective_date", LocalDate.class),
                        rs.getBigDecimal("rate"), rs.getBigDecimal("previous_rate"),
                        rs.getBigDecimal("change_amount"), rs.getBigDecimal("change_percent"),
                        rs.getObject("fetched_at", OffsetDateTime.class).toInstant()), date);
    }
}
