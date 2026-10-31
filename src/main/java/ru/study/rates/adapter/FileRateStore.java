package ru.study.rates.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import ru.study.rates.model.CurrencyRate;

/** Дополнительное файловое хранилище; H2 остаётся источником данных для API. */
public class FileRateStore {
    private final Path directory;
    private final ObjectMapper mapper;

    public FileRateStore(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    public void save(LocalDate date, List<CurrencyRate> rates) throws IOException {
        Files.createDirectories(directory);
        var temporary = Files.createTempFile(directory, ".rates-", ".tmp");
        try {
            Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(rates));
            // Читатель видит целиком старый или новый снимок. При ошибке старый остаётся.
            Files.move(temporary, directory.resolve(date + ".json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
