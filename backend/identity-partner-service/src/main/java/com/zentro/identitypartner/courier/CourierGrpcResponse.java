package com.zentro.identitypartner.courier;

public record CourierGrpcResponse(
    String courierId,
    boolean active) {}
