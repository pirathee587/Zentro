package com.zentro.identitypartner.user;

import java.util.List;
import java.util.UUID;

public record UserProfile(
    UUID id,
    String email,
    String name,
    String phone,
    boolean enabled,
    String accountStatus,
    String merchantStatus,
    List<String> roles) {}
