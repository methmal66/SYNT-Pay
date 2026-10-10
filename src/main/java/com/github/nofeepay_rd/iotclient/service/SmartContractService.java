package com.github.nofeepay_rd.iotclient.service;
import org.hyperledger.fabric.client.Contract;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
/*
@Service
public class SmartContractService {

    //This merchant address should be moved to application.properties later
    private final String merchantAddress = "merc001";

    //This method should implements the smart contract integration logic
    public String transferFunds(String customerAddress, float amount) {
        System.out.println("Transfer " + amount + " from " + customerAddress + " to " + this.merchantAddress);
        return "Status from smart contract";
    }
    */
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
    }/*

    public String transferFunds(
            String customerAddress,
            double amount,
            String merchantAddress) throws Exception {

        byte[] result = contract.submitTransaction(
                "TransferFunds",
                customerAddress,
                String.valueOf(amount),
                merchantAddress
        );

        return new String(
                result,
                StandardCharsets.UTF_8
        );
    }*/
    public byte[] transferFunds(
            String customerAddress,
            long amount,
            String merchantAddress) {

        try {
            return contract.submitTransaction(
                    "TransferFunds",
                    customerAddress,
                    String.valueOf(amount),
                    merchantAddress
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(
                    "Fabric TransferFunds transaction failed", e
            );
        }
    }
}