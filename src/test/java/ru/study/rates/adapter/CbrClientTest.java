package ru.study.rates.adapter;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import ru.study.rates.model.Currency;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CbrClientTest {
    private final LocalDate date = LocalDate.of(2024, 3, 5);
    private MockRestServiceServer server;
    private CbrClient client;

    @BeforeEach
    void setUp() {
        var template = new RestTemplate();
        server = MockRestServiceServer.createServer(template);
        client = new CbrClient(template);
    }

    @Test
    void parsesWindows1251AndNormalizesNominal() {
        respond(xml());
        var snapshot = client.fetch(date);
        assertThat(snapshot.effectiveDate()).isEqualTo(date);
        assertThat(snapshot.quotes()).hasSize(5);
        assertThat(snapshot.quotes().get(Currency.USD).name()).isEqualTo("Доллар США");
        assertThat(snapshot.quotes().get(Currency.JPY).rublesPerUnit()).isEqualByComparingTo("0.600123");
        assertThatThrownBy(() -> snapshot.quotes().clear()).isInstanceOf(UnsupportedOperationException.class);
        server.verify();
    }

    @Test
    void acceptsOlderEffectiveDateOnWeekend() {
        respond(xml().replace("05.03.2024", "02.03.2024"));
        assertThat(client.fetch(date).effectiveDate()).isEqualTo(LocalDate.of(2024, 3, 2));
    }

    @Test
    void rejectsMissingCurrencies() {
        respond(xml().replace("<CharCode>JPY</CharCode>", "<CharCode>AUD</CharCode>"));
        assertThatThrownBy(() -> client.fetch(date)).isInstanceOf(SourceException.class)
                .hasMessageContaining("не все пять");
    }

    @Test
    void rejectsInvalidNominal() {
        respond(xml().replace("<Nominal>100</Nominal>", "<Nominal>0</Nominal>"));
        assertThatThrownBy(() -> client.fetch(date)).isInstanceOf(SourceException.class);
    }

    @Test
    void rejectsFutureEffectiveDate() {
        respond(xml().replace("05.03.2024", "06.03.2024"));
        assertThatThrownBy(() -> client.fetch(date)).isInstanceOf(SourceException.class)
                .hasMessageContaining("более позднюю дату");
    }

    @Test
    void rejectsExternalEntities() {
        respond("""
                <?xml version="1.0"?>
                <!DOCTYPE ValCurs [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <ValCurs Date="05.03.2024">&xxe;</ValCurs>
                """);
        assertThatThrownBy(() -> client.fetch(date)).isInstanceOf(SourceException.class);
    }

    @Test
    void reportsHttpFailure() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.fetch(date)).isInstanceOf(SourceException.class);
    }

    @Test
    void reportsNetworkFailure() {
        server.expect(anything()).andRespond(withException(new IOException("Timeout")));
        assertThatThrownBy(() -> client.fetch(date)).isInstanceOf(SourceException.class);
    }

    @Test
    void rejectsRedirectAndOversizedResponse() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.FOUND));
        assertThatThrownBy(() -> client.fetch(date)).isInstanceOf(SourceException.class);
        server.reset();
        server.expect(anything()).andRespond(withSuccess(new byte[2 * 1024 * 1024 + 1], MediaType.TEXT_XML));
        assertThatThrownBy(() -> client.fetch(date)).isInstanceOf(SourceException.class)
                .hasMessageContaining("размер");
    }

    @Test
    void acceptsOnlySupportedSourceLinks() {
        client.validateSource(null);
        client.validateSource(CbrClient.SOURCE_URL);
        client.validateSource("https://cbr.ru/scripts/XML_daily.asp");
        for (String source : new String[]{"", "http://localhost", "https://www.cbr.ru.evil.org/currency_base/daily/",
                "https://www.cbr.ru:443/currency_base/daily/", "https://www.cbr.ru/currency_base/daily/?date=1",
                "https://user@www.cbr.ru/currency_base/daily/", "https://coinmarketcap.com/"}) {
            assertThatThrownBy(() -> client.validateSource(source)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private void respond(String xml) {
        server.expect(requestTo("https://www.cbr.ru/scripts/XML_daily.asp?date_req=05/03/2024"))
                .andRespond(withSuccess(xml.getBytes(Charset.forName("windows-1251")), MediaType.TEXT_XML));
    }

    private String xml() {
        return """
                <?xml version="1.0" encoding="windows-1251"?>
                <ValCurs Date="05.03.2024">
                  <Valute><CharCode>USD</CharCode><Nominal>1</Nominal><Name>Доллар США</Name><Value>90,5000</Value></Valute>
                  <Valute><CharCode>EUR</CharCode><Nominal>1</Nominal><Name>Евро</Name><Value>98,0000</Value></Valute>
                  <Valute><CharCode>CNY</CharCode><Nominal>1</Nominal><Name>Юань</Name><Value>12,5000</Value></Valute>
                  <Valute><CharCode>GBP</CharCode><Nominal>1</Nominal><Name>Фунт</Name><Value>115,0000</Value></Valute>
                  <Valute><CharCode>JPY</CharCode><Nominal>100</Nominal><Name>Иен</Name><Value>60,0123</Value></Valute>
                  <Valute><CharCode>AUD</CharCode><Nominal>1</Nominal><Name>Другой доллар</Name><Value>59,0000</Value></Valute>
                </ValCurs>
                """;
    }
}
