package ru.study.rates.config;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.core.io.ClassPathResource;

final class CbrTls {
    private CbrTls() { }

    static SSLContext createContext() throws GeneralSecurityException, IOException {
        // В некоторых JDK 17 отсутствует HARICA 2021, используемый сайтом ЦБ.
        // Сохраняем стандартные доверенные CA и добавляем один проверенный публичный root.
        // Изменение действует только для этого клиента, системный truststore не меняется.
        var defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore) null);
        var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int index = 0;
        for (var manager : defaults.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                for (var certificate : x509.getAcceptedIssuers()) {
                    trustStore.setCertificateEntry("default-" + index++, certificate);
                }
            }
        }
        try (var input = new ClassPathResource("certs/harica-tls-rsa-root-2021.pem").getInputStream()) {
            var certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            trustStore.setCertificateEntry("harica-tls-rsa-2021", certificate);
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        var context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
