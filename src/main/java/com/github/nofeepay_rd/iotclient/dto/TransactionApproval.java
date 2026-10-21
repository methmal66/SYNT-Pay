package com.github.nofeepay_rd.iotclient.dto;

import lombok.Data;

@Data
public class TransactionApproval {
    private String customerAddress;
    private float amount;
    private String merchantAddress;
}
