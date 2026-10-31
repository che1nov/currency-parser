# Результаты проверки версии 1.1.0

Проверено 31 октября 2026 года: Java 17, Maven 3.9.11, Spring Boot 3.5.16.

## Автоматические тесты

Команда: `mvn -B -ntp verify` из папки проекта.

```text
CbrClientTest:          10 tests, 0 failures, 0 errors
CbrTlsTest:              1 test,  0 failures, 0 errors
CollectionScheduleTest:  3 tests, 0 failures, 0 errors
CollectionJournalTest:   4 tests, 0 failures, 0 errors
FileRateStoreTest:       2 tests, 0 failures, 0 errors
RateApplicationTest:    12 tests, 0 failures, 0 errors
Total:                 32 tests, 0 failures, 0 errors, 0 skipped
BUILD SUCCESS
```

Интеграционные тесты используют настоящий Spring MVC, сервисы, JDBC, H2 и транзакции. Подменён внешний клиент ЦБ, а ошибки диска/БД инъецируются в отдельных тестах. Файловые тесты работают во временных каталогах и не меняют пользовательскую БД.

Дополнительные проверки критериев:

- Два HTTP-запроса выполняются одновременно в разных `cbr-worker-*`; проверка через `CountDownLatch`.
- Собственный `ScheduledExecutorService` продолжает повторные запуски после ошибки источника и конфликта сборов.
- При остановке планировщик прерывает активный сбор и дожидается завершения своего потока.
- Самостоятельный `Runnable` журнала запущен через `new Thread`, с `daemon=true`.
- Несколько производителей передают события через ограниченную `BlockingQueue`; принятые события записываются без дублей и потерь при штатной остановке.
- Проверены заполнение очереди, отклонение лишнего события, конкурентное закрытие, дописывание очереди, остановка потока и сохранение журнала при повторном открытии.
- JSON-снимки читаются обратно, повторный сбор заменяет файл; при ошибке замены временный файл удаляется.
- Ошибка источника или откат H2 не меняют предыдущий снимок; ошибка записи снимка отображается в статусе и не отменяет уже зафиксированную H2.

## Сквозная проверка реального JAR

Команда: `python3 scripts/smoke_check.py`. Источник — настоящий `https://www.cbr.ru`, без подмены ответов. Временная файловая H2, порт 18080.

```text
PASS: startup schedule collected five real CBR rates
PASS: historical collection, currency filter, numeric sorting and daily change
PASS: repeated collection has no duplicates; invalid requests return HTTP 400
PASS: JSON snapshot matches collected rates; standalone journal thread is a daemon
PASS: graceful shutdown drained all six journal events to file
PASS: file H2 retains identical results after restart, without fetching again
PASS: both application processes stopped gracefully
```

Проверены текущая дата и `2024-03-05`, пять валют, сортировка числовых курсов, получение USD, абсолютное и процентное изменение, повторный сбор, HTTP 400 и пустой результат для несобранной даты. JSON-снимок совпал с ответом успешного сбора. После штатной остановки все шесть событий трёх сборов присутствовали в журнале, каждое с `thread=collection-journal` и `daemon=true`.

После перезапуска без расписания ответы H2 совпали со всеми ранее сохранёнными полями, включая `fetchedAt`. JSON-снимок и журнал не изменились. Оба процесса завершились штатно.

Публичный HARICA TLS RSA Root CA 2021 сохраняет совместимость HTTPS-клиента с используемой Java 17. Проверка цепочки сертификатов и имени хоста не отключается. Подробнее: `src/main/resources/certs/README.txt`.

Готовый архив включает исходники, тесты, конфигурацию, README, HTTP-примеры и JAR со всеми зависимостями. Пользовательская БД, журнал и снимки в архив не включены. Для новых курсов нужен доступ к сайту ЦБ. Между H2 и дополнительным JSON-хранилищем нет распределённой транзакции; поведение при ошибках описано в README.
