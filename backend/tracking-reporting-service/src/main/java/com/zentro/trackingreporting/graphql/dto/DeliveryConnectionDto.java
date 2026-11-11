package com.zentro.trackingreporting.graphql.dto;

import java.util.List;

public record DeliveryConnectionDto(
    List<DeliveryDetailsDto> items,
    PageInfoDto pageInfo
) {}

