package com.sakurabank.core;

import java.util.UUID;

public final class SystemAccounts {

    private SystemAccounts() {}

    public static final UUID CLEARING_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
}