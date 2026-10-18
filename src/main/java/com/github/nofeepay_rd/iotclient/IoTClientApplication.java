package com.github.nofeepay_rd.iotclient;

import com.github.nofeepay_rd.iotclient.service.SmartContractService;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IoTClientApplication implements CommandLineRunner {

    private final SmartContractService smartContractService;

    public IoTClientApplication(
            SmartContractService smartContractService) {

        this.smartContractService = smartContractService;
    }

    public static void main(String[] args) {
        SpringApplication.run(IoTClientApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {

        // Test merchant digital signature
        //String transactionData = "TX001|C001|M001|100.00|LKR";

        //String signature = signatureService.signTransaction(transactionData);

        // System.out.println("Merchant signature: " + signature);

        // Keep your existing Fabric transaction call
        smartContractService.TransferFunds("C001", 100, "M001");
    }
}
