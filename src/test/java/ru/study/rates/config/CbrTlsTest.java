package ru.study.rates.config;

import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class CbrTlsTest {
    @Test
    void bundledCaMatchesPubliclyVerifiedFingerprint() throws Exception {
        try (var input = new ClassPathResource("certs/harica-tls-rsa-root-2021.pem").getInputStream()) {
            var certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded()));
            assertThat(fingerprint).isEqualTo("d95d0e8eda79525bf9beb11b14d2100d3294985f0c62d9fabd9cd999eccb7b1d");
            assertThat(certificate.getBasicConstraints()).isNotNegative();
            certificate.verify(certificate.getPublicKey());
        }
        assertThat(CbrTls.createContext().getSocketFactory()).isNotNull();
    }
}
