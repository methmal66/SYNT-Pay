package com.github.nofeepay_rd.cashmanager;

import com.github.nofeepay_rd.cashmanager.service.CustodialService;
import com.github.nofeepay_rd.cashmanager.service.SmartContractService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CashManagerApplication implements CommandLineRunner {

    private final CustodialService custodialService;
    private final SmartContractService smartContractService;

    public CashManagerApplication(SmartContractService smartContractService, CustodialService custodialService){
        this.smartContractService = smartContractService;
        this.custodialService = custodialService;
    }

    public static void main(String[] args) {
        SpringApplication.run(CashManagerApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        custodialService.IndexPendingDeposits("/file/path/to/bulkdeposits/DEPOSIT_YYYYMMDD_1.CSV");
        System.out.println("Read pending deposits on the correct time");
        System.out.println("Loop through each pending deposit and invoke the smart contract function");
        smartContractService.DepositFunds("cust001", 10000);
    }
}
