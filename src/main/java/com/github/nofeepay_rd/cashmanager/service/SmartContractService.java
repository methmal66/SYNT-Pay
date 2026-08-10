package com.github.nofeepay_rd.cashmanager.service;

import org.springframework.stereotype.Service;

// All the business logic related to smart contract integration goes here
@Service
public class SmartContractService {

    public void DepositFunds(String customerAddress, float amount) {
        System.out.println("Deposit " + amount + " to "+ customerAddress);
        System.out.println("Update deposit status from pending to complete in DB");
    }
}
