package ru.study.rates.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.study.rates.model.Currency;
import ru.study.rates.model.CurrencyRate;
import static org.assertj.core.api.Assertions.*;

class FileRateStoreTest {
    @TempDir Path directory;
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final LocalDate date = LocalDate.of(2024, 3, 5);

    @Test
    void storesReadableSnapshotAndReplacesSameDateWithoutTemporaryFiles() throws Exception {
        var store = new FileRateStore(directory, mapper);
        var rates = List.of(rate("90"));
        store.save(date, rates);
        assertThat(mapper.readValue(directory.resolve(date + ".json").toFile(),
                new TypeReference<List<CurrencyRate>>() { })).isEqualTo(rates);
        store.save(date, List.of(rate("91")));
        assertThat(mapper.readTree(directory.resolve(date + ".json").toFile()).get(0).get("rate").decimalValue())
                .isEqualByComparingTo("91");
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly(date + ".json");
        }
    }

    @Test
    void cleansTemporaryFileWhenReplacementFails() throws Exception {
        var target = directory.resolve(date + ".json");
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep.txt"), "existing");
        var store = new FileRateStore(directory, mapper);
        assertThatThrownBy(() -> store.save(date, List.of(rate("90")))).isInstanceOf(IOException.class);
        assertThat(Files.readString(target.resolve("keep.txt"))).isEqualTo("existing");
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactly(target);
        }
    }

    private CurrencyRate rate(String value) {
        return new CurrencyRate(date, Currency.USD, "Доллар США", date, date.minusDays(1),
                new BigDecimal(value), new BigDecimal("89"), BigDecimal.ONE, BigDecimal.ONE, Instant.EPOCH);
    }
}
