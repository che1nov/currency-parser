package ru.study.rates.adapter;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.EnumMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Element;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.SAXParseException;
import ru.study.rates.model.Currency;
import ru.study.rates.model.RateSnapshot;

@Component
public class CbrClient {
    public static final String SOURCE_URL = "https://www.cbr.ru/currency_base/daily/";
    private static final String XML_URL = "https://www.cbr.ru/scripts/XML_daily.asp";
    private static final int MAX_XML_BYTES = 2 * 1024 * 1024;
    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final DateTimeFormatter XML_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Logger log = LoggerFactory.getLogger(CbrClient.class);
    private final RestTemplate restTemplate;

    public CbrClient(RestTemplate cbrRestTemplate) {
        this.restTemplate = cbrRestTemplate;
    }

    // URL пользователя выбирает поддерживаемый источник; сетевой адрес всегда фиксирован.
    public void validateSource(String sourceUrl) {
        if (sourceUrl == null) {
            return;
        }
        try {
            var uri = URI.create(sourceUrl);
            boolean allowedPath = "/currency_base/daily/".equals(uri.getPath())
                    || "/scripts/XML_daily.asp".equals(uri.getPath());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !("www.cbr.ru".equalsIgnoreCase(uri.getHost()) || "cbr.ru".equalsIgnoreCase(uri.getHost()))
                    || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !allowedPath) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("sourceUrl: используйте " + SOURCE_URL
                    + " или " + XML_URL + "; дату передавайте в поле date");
        }
    }

    public RateSnapshot fetch(LocalDate date) {
        var uri = URI.create(XML_URL + "?date_req=" + REQUEST_DATE.format(date));
        log.info("Fetching CBR rates date={}", date);
        try {
            byte[] body = restTemplate.execute(uri, HttpMethod.GET, null, response -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new SourceException("ЦБ РФ вернул неожиданный HTTP-статус");
                }
                byte[] bytes = response.getBody().readNBytes(MAX_XML_BYTES + 1);
                if (bytes.length > MAX_XML_BYTES) {
                    throw new SourceException("Ответ ЦБ РФ превышает допустимый размер");
                }
                return bytes;
            });
            return parse(body, date);
        } catch (SourceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SourceException("Не удалось получить корректные курсы ЦБ РФ", ex);
        }
    }

    private RateSnapshot parse(byte[] xml, LocalDate requestedDate) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void fatalError(SAXParseException ex) throws SAXParseException { throw ex; }
        });
        var root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
        if (!"ValCurs".equals(root.getTagName())) {
            throw new SourceException("В ответе ЦБ РФ отсутствует ValCurs");
        }
        var effectiveDate = LocalDate.parse(root.getAttribute("Date"), XML_DATE);
        if (effectiveDate.isAfter(requestedDate)) {
            throw new SourceException("ЦБ РФ вернул курсы на более позднюю дату");
        }
        var quotes = new EnumMap<Currency, RateSnapshot.Quote>(Currency.class);
        var nodes = root.getElementsByTagName("Valute");
        for (int i = 0; i < nodes.getLength(); i++) {
            var element = (Element) nodes.item(i);
            Currency currency;
            try {
                currency = Currency.valueOf(text(element, "CharCode"));
            } catch (IllegalArgumentException ex) {
                continue; // ЦБ публикует больше валют, чем поддерживает приложение.
            }
            var nominal = decimal(text(element, "Nominal"));
            var value = decimal(text(element, "Value"));
            if (nominal.signum() <= 0 || value.signum() <= 0) {
                throw new SourceException("ЦБ РФ вернул неположительный номинал или курс");
            }
            var quote = new RateSnapshot.Quote(text(element, "Name"),
                    value.divide(nominal, 10, RoundingMode.HALF_UP));
            if (quotes.put(currency, quote) != null) {
                throw new SourceException("ЦБ РФ вернул дубликат валюты");
            }
        }
        if (quotes.size() != Currency.values().length) {
            throw new SourceException("Ответ ЦБ РФ содержит не все пять валют");
        }
        return new RateSnapshot(effectiveDate, quotes);
    }

    private static String text(Element element, String tag) {
        var node = element.getElementsByTagName(tag).item(0);
        if (node == null || node.getTextContent().isBlank()) {
            throw new SourceException("В ответе ЦБ РФ отсутствует поле " + tag);
        }
        return node.getTextContent().trim();
    }

    private static BigDecimal decimal(String text) {
        return new BigDecimal(text.replace("\u00a0", "").replace(" ", "").replace(',', '.'));
    }
}
