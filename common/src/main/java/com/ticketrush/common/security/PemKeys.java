package com.ticketrush.common.security;

import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.function.Function;

/** Loads RSA keys from PEM resources (X.509 public, PKCS#8 private). Fails fast with the location in the message. */
public final class PemKeys {

    private PemKeys() {
    }

    public static RSAPublicKey publicKey(Resource location) {
        return read(location, in -> RsaKeyConverters.x509().convert(in));
    }

    public static RSAPrivateKey privateKey(Resource location) {
        return read(location, in -> RsaKeyConverters.pkcs8().convert(in));
    }

    private static <T> T read(Resource location, Function<InputStream, T> parser) {
        if (location == null || !location.exists()) {
            throw new IllegalStateException("RSA key not found: " + (location == null ? "null" : location.getDescription()));
        }
        try (InputStream in = location.getInputStream()) {
            T key = parser.apply(in);
            if (key == null) {
                throw new IllegalStateException("RSA key could not be parsed: " + location.getDescription());
            }
            return key;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("RSA key could not be read: " + location.getDescription(), e);
        }
    }
}
