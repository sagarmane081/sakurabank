package com.sakurabank.core.domain;

import java.util.UUID;

public class SystemAccountTransferNotAllowedException
        extends RuntimeException {

    public SystemAccountTransferNotAllowedException(UUID accountId) {
        super("SYSTEM account cannot participate in a public transfer: "
                + accountId);
    }
}