package com.github.nofeepay_rd.iotclient.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

@Service
public class SignatureService {

    private final Path privateKeyPath = Path.of( "C:\\Users\\yehan\\IdeaProjects\\merchant-private.txt"
    );

    private PrivateKey loadMerchantPrivateKey() throws Exception {

        if (!Files.exists(privateKeyPath)) {
            throw new IllegalStateException(
                    "Private key file not found: " + privateKeyPath
            );
        }

        String pem = Files.readString(privateKeyPath);

        String keyContent = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder()
                .decode(keyContent);

        PKCS8EncodedKeySpec keySpec =
                new PKCS8EncodedKeySpec(keyBytes);

        KeyFactory keyFactory =
                KeyFactory.getInstance("RSA");

        return keyFactory.generatePrivate(keySpec);
    }

    public String signTransaction(
            String transactionData) throws Exception {

        PrivateKey privateKey = loadMerchantPrivateKey();

        Signature signer =
                Signature.getInstance("SHA256withRSA");

        signer.initSign(privateKey);

        signer.update(
                transactionData.getBytes(StandardCharsets.UTF_8)
        );

        byte[] signedBytes = signer.sign();

        return Base64.getEncoder()
                .encodeToString(signedBytes);
    }
}
