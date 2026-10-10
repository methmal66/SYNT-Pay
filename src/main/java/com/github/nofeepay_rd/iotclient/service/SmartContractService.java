package com.github.nofeepay_rd.iotclient.service;
import org.hyperledger.fabric.client.Contract;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class SmartContractService {

    private final Contract contract;

    public SmartContractService(Contract contract) {
        this.contract = contract;
    }

    public String GetAllCustomerTransfers() throws Exception {

        byte[] result = contract.evaluateTransaction(
                "GetAllCustomerTransfers"
        );

        return new String(
                result,
                StandardCharsets.UTF_8
        );
    }
    public void TransferFunds(
            String customerAddress,
            double amount,
            String merchantAddress) {

        try {
            contract.submitTransaction(
                    "TransferFunds",
                    customerAddress,
                    String.valueOf(amount),
                    merchantAddress);
            System.out.println("Transaction Pass");

        } catch (Exception e) {
            //e.printStackTrace();
            System.out.println("Transaction Fail");
        }
    }
}