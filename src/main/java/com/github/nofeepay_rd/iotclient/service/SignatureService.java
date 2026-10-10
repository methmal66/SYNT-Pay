package com.github.nofeepay_rd.iotclient.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

    @Service
    public class SignatureService {

        public KeyPair generateKeyPair() throws Exception {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");

            generator.initialize(2048);

            return generator.generateKeyPair();
        }

        public String signTransaction(
                String transactionData,
                PrivateKey privateKey) throws Exception {

            Signature signer =
                    Signature.getInstance("SHA256withRSA");

            signer.initSign(privateKey);

            signer.update(
                    transactionData.getBytes(
                            StandardCharsets.UTF_8));

            byte[] signature = signer.sign();

            return Base64.getEncoder()
                    .encodeToString(signature);
        }

        public boolean verifyTransaction(
                String transactionData,
                String encodedSignature,
                PublicKey publicKey) throws Exception {

            Signature verifier =
                    Signature.getInstance("SHA256withRSA");

            verifier.initVerify(publicKey);

            verifier.update(
                    transactionData.getBytes(
                            StandardCharsets.UTF_8));

            byte[] signature =
                    Base64.getDecoder()
                            .decode(encodedSignature);

            return verifier.verify(signature);
        }
    }


