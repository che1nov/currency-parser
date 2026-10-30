# Результаты проверки

Проверено 30 октября 2026 года: Java 17, Maven 3.9.11, Spring Boot 3.5.16.

## Автоматические тесты

Команда: `mvn -B -ntp verify` из папки проекта.

```text
CbrClientTest:       10 tests, 0 failures, 0 errors
CbrTlsTest:           1 test,  0 failures, 0 errors
RateApplicationTest: 10 tests, 0 failures, 0 errors
Total:              21 tests, 0 failures, 0 errors, 0 skipped
BUILD SUCCESS
```

В интеграционных тестах участвуют настоящий Spring MVC, сервисы, JDBC, H2 и транзакции. Подменён только внешний HTTP-клиент ЦБ. Отдельно проверяются разбор XML через RestTemplate и идентичность публичного CA. Параллельность проверяется барьером `CountDownLatch`: оба запроса должны войти в работу до разрешения завершиться; используются два разных потока `cbr-worker-*`.

## Сквозная проверка реального JAR

Команда: `python3 scripts/smoke_check.py`. Источник — настоящий `https://www.cbr.ru`, без подмены ответов. Временная файловая H2, порт 18080.

```text
PASS: startup schedule collected five real CBR rates
PASS: historical collection, currency filter, numeric sorting and daily change
PASS: repeated collection has no duplicates; invalid requests return HTTP 400
PASS: file H2 retains identical results after restart, without fetching again
PASS: both application processes stopped gracefully
```

Проверены текущая дата и `2024-03-05`, пять валют, сортировка числовых курсов, получение USD, абсолютное и процентное изменение, повторный сбор, HTTP 400 и пустой результат для несобранной даты. После перезапуска без расписания ответы совпали со всеми ранее сохранёнными полями, включая `fetchedAt`.

При проверке обнаружена несовместимость стандартного truststore используемой Java 17 с CA сайта ЦБ. В клиент добавлен публичный HARICA TLS RSA Root CA 2021, отпечаток сверен с реестром CA; повторная проверка HTTPS успешна. Подробности — в `src/main/resources/certs/README.txt`. Отключения проверки TLS нет.

Готовый архив включает исходники, тесты, конфигурацию, README, HTTP-примеры и запускаемый JAR со всеми зависимостями. Локальная БД и временные файлы сборки в архив не включены. Для новых курсов требуется доступ к сайту ЦБ; сетевые ошибки не заменяются вымышленными данными.
