package com.github.nofeepay_rd.iotclient;
import com.github.nofeepay_rd.iotclient.service.SmartContractService;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.identity.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/*
@SpringBootApplication
public class IoTClientApplication implements CommandLineRunner {

    private final SmartContractService smartContractService;

    public IoTClientApplication(SmartContractService smartContractService) throws GatewayException {
        this.smartContractService = smartContractService;
    }

    public static void main(String[] args) {
        SpringApplication.run(IoTClientApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        String response = smartContractService.transferFunds("cust001", 1000);
        System.out.println(response);
    }

    ////*/

@SpringBootApplication
public class IoTClientApplication implements CommandLineRunner {

    private final SmartContractService smartContractService;

    public IoTClientApplication(SmartContractService smartContractService) {

        this.smartContractService = smartContractService;
    }

    public static void main(String[] args) {

        SpringApplication.run(IoTClientApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {

        System.out.println("Testing Fabric connection...");
        String result = smartContractService.GetAllCustomerTransfers();
        System.out.println("Result from blockchain:");
        System.out.println(result);
    }
}

