package com.github.nofeepay_rd.iotclient.service;
import com.github.nofeepay_rd.iotclient.dto.TransactionApproval;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.client.Contract;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
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
    public void TransferFunds(TransactionApproval approval) throws Exception {

        try {
            contract.submitTransaction(
                    "TransferFunds",
                    approval.getCustomerAddress(),
                    String.valueOf(approval.getAmount()),
                    approval.getMerchantAddress());
            log.info("Transaction Processed Successfully! {}", approval);

        } catch (Exception e) {
            log.error("Transaction Failed! {}", approval, e);
            throw new Exception(e.getMessage());
        }
    }
}