package com.github.nofeepay_rd.settlementprocessor.service;

import org.springframework.stereotype.Service;

// All the business logic related to custodial bank integration goes here
@Service
public class CustodialService {

    public void SendSettlementTransactions(){
        System.out.println("Insert the queried settlement records to DB with pending status");
        System.out.println("Send settlement transactions to bank");
        System.out.println("Update settlement status as approved once payment is completed from bank");
    }
}
