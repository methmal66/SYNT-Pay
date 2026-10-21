package com.github.nofeepay_rd.iotclient.controller;

import com.github.nofeepay_rd.iotclient.dto.TransactionApproval;
import com.github.nofeepay_rd.iotclient.service.SmartContractService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@RestController
@RequestMapping("/")
class NFCController {

    @Value("${app.merchant-address}")
    private String merchantAddress;

    @Value("${app.test-amount}")
    private float testAmount;

    final private SmartContractService smartContractService;

    public NFCController(SmartContractService smartContractService) {
        this.smartContractService = smartContractService;
    }


    @PostMapping("/initiateTransaction")
    public ResponseEntity<String> initiateTransaction(@RequestBody TransactionApproval approval) {

        if (approval.getCustomerAddress() == null || approval.getCustomerAddress().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Customer Address is required!");
        }

        approval.setMerchantAddress(merchantAddress);
        approval.setAmount(testAmount);

        try {
            this.smartContractService.TransferFunds(approval);
            return ResponseEntity.status(HttpStatus.OK).body("Transaction Processed Successfully!");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}
