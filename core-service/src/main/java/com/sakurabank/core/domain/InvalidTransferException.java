package com.sakurabank.core.domain;

import java.util.UUID;

public class InvalidTransferException extends RuntimeException {

  public InvalidTransferException(UUID accountId) {
    super("Cannot transfer funds to the same account: " + accountId);
  }
}
