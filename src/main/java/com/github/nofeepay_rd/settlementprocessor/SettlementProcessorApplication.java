package com.github.nofeepay_rd.settlementprocessor;

import com.github.nofeepay_rd.settlementprocessor.service.CustodialService;
import com.github.nofeepay_rd.settlementprocessor.service.SmartContractService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SettlementProcessorApplication implements CommandLineRunner {

    private final CustodialService custodialService;
    private final SmartContractService smartContractService;

    public SettlementProcessorApplication(CustodialService custodialService, SmartContractService smartContractService) {
        this.custodialService = custodialService;
        this.smartContractService = smartContractService;
    }


    public static void main(String[] args) {
        SpringApplication.run(SettlementProcessorApplication.class, args);
    }


    @Override
    public void run(String... args) throws Exception {
        smartContractService.CalculateTodaysSettlement("merch001", "20260815");
        custodialService.SendSettlementTransactions();
    }
}
