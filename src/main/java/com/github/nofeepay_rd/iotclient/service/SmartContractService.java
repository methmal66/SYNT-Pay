package com.github.nofeepay_rd.iotclient.service;

import org.springframework.stereotype.Service;

@Service
public class SmartContractService {

    //This merchant address should be moved to application.properties later
    private final String merchantAddress = "merc001";

    //This method should implements the smart contract integration logic
    public String TransferFunds(String customerAddress, float amount) {
        System.out.println("Transfer " + amount + " from " + customerAddress + " to " + this.merchantAddress);
        return "Status from smart contract";
    }
}
