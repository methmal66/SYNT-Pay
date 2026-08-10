package com.github.nofeepay_rd.iotclient;

import com.github.nofeepay_rd.iotclient.service.SmartContractService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IoTClientApplication implements CommandLineRunner {

    private final SmartContractService smartContractService;

    public IoTClientApplication(SmartContractService smartContractService){
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
}
