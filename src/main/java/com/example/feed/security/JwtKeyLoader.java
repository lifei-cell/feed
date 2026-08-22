package com.example.feed.security;

import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class JwtKeyLoader {
    private JwtKeyLoader() {
    }

    static RSAPublicKey publicKey(ResourceLoader resources, String location) {
        return (RSAPublicKey) generate(resources, location, "PUBLIC KEY", true);
    }

    static RSAPrivateKey privateKey(ResourceLoader resources, String location) {
        return (RSAPrivateKey) generate(resources, location, "PRIVATE KEY", false);
    }

    private static java.security.Key generate(ResourceLoader resources, String location,
                                               String type, boolean publicKey) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("RSA JWT requires a " + type.toLowerCase()
                    + " resource location");
        }
        try (var input = resources.getResource(location).getInputStream()) {
            String pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII)
                    .replace("-----BEGIN " + type + "-----", "")
                    .replace("-----END " + type + "-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(pem);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return publicKey
                    ? factory.generatePublic(new X509EncodedKeySpec(encoded))
                    : factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load RSA JWT " + type.toLowerCase()
                    + " from " + location, exception);
        }
    }
}
