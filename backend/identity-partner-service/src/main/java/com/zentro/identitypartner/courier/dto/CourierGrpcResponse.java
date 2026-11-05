package com.zentro.identitypartner.courier.dto;

public record CourierGrpcResponse(
    String courierId,
    boolean active) {}
