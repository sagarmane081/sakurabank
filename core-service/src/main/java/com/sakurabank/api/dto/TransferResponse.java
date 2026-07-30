package com.sakurabank.api.dto;

import java.util.UUID;

public record TransferResponse(

        UUID idempotencyKey,
        String status

){}