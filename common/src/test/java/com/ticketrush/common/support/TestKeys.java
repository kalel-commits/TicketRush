package com.ticketrush.common.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** Test-only RSA key pairs generated in memory. */
public record TestKeys(RSAPublicKey pub, RSAPrivateKey priv) {

    public static TestKeys rsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            return new TestKeys((RSAPublicKey) kp.getPublic(), (RSAPrivateKey) kp.getPrivate());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String publicPem() {
        return pem("PUBLIC KEY", pub.getEncoded());
    }

    public String privatePem() {
        return pem("PRIVATE KEY", priv.getEncoded());
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
