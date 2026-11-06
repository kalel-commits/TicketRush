package com.ticketrush.user;

import com.ticketrush.common.security.PemKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/** Loads the RSA key pair at startup; a missing or malformed file stops the service with the path in the error. */
@Configuration
public class JwtKeysConfig {

    @Bean
    RSAPublicKey jwtPublicKey(@Value("${ticketrush.jwt.public-key}") Resource location) {
        return PemKeys.publicKey(location);
    }

    @Bean
    RSAPrivateKey jwtPrivateKey(@Value("${ticketrush.jwt.private-key}") Resource location) {
        return PemKeys.privateKey(location);
    }
}
