package com.github.nofeepay_rd.iotclient.config;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;

import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.Hash;
import org.hyperledger.fabric.client.Network;

import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

@Configuration
public class FabricConfig {
    @Bean
    public Gateway fabricGateway() throws Exception {
        //Load Org1 X.509 certificate
        Path certificatePath = Paths.get("C:\\Users\\yehan\\organizations\\organizations\\peerOrganizations\\org1.example.com\\users\\User1@org1.example.com\\msp\\signcerts\\User1@org1.example.com-cert.pem");
        X509Certificate certificate;
        try (Reader certReader =
                     Files.newBufferedReader(certificatePath)) {
            certificate =
                    Identities.readX509Certificate(certReader);
        }
        Identity identity = new X509Identity(
                "Org1MSP",
                            certificate
                );
        //Find and load private key
        Path keyDirectory = Paths.get("C:\\Users\\yehan\\organizations\\organizations\\peerOrganizations\\org1.example.com\\users\\User1@org1.example.com\\msp\\keystore");
        Path privateKeyPath;
        try (var keyFiles = Files.list(keyDirectory)) {
            privateKeyPath = keyFiles
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(
                            () -> new RuntimeException(
                                    "Private key not found"
                            )
                    );
        }
        PrivateKey privateKey;
        try (Reader keyReader =
                     Files.newBufferedReader(privateKeyPath)) {
            privateKey =
                    Identities.readPrivateKey(keyReader);
        }
        Signer signer =
                Signers.newPrivateKeySigner(privateKey);
        //Load TLS CA certificate
        Path tlsCertificatePath = Paths.get("C:\\Users\\yehan\\organizations\\organizations\\peerOrganizations\\org1.example.com\\users\\User1@org1.example.com\\tls\\ca.crt");

        ChannelCredentials tlsCredentials =
                TlsChannelCredentials
                        .newBuilder()
                        .trustManager(
                                tlsCertificatePath.toFile()
                        )
                        .build();
        //Connect to Fabric peer / Gateway
        String gatewayEndpoint = "10.247.131.112:7051";
        ManagedChannel grpcChannel =
                Grpc.newChannelBuilder(
                                gatewayEndpoint,
                                tlsCredentials
                        )

                        // Important because TLS cert identifies
                        // peer0.org1.example.com
                        .overrideAuthority(
                                "peer0.org1.example.com"
                        )
                        .build();
         //Build Fabric Gateway connection
        return Gateway.newInstance()
                .identity(identity)
                .signer(signer)
                .hash(Hash.SHA256)
                .connection(grpcChannel)
                .connect();
    }
    @Bean
    public Network network(
            Gateway gateway) {

        return gateway.getNetwork(
                "coinlog"
        );
    }
    @Bean
    public Contract contract(
            Network network) {

        return network.getContract(
                "nofeepay"
        );
    }
}