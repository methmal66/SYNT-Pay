package com.github.nofeepay_rd.cashmanager.service;

import org.springframework.stereotype.Service;

// All the business logic related to custodial bank integration goes here
@Service
public class CustodialService {

    public void IndexPendingDeposits(String csvPath) {
        System.out.println("Read csv file received from custodial bank "+ csvPath);
        System.out.println("Insert those record to DB as pending deposits");
    }
}
