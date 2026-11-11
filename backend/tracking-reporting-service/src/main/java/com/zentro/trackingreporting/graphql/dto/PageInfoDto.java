package com.zentro.trackingreporting.graphql.dto;

public record PageInfoDto(
    int page,
    int size,
    int totalElements,
    int totalPages,
    boolean hasNext,
    boolean hasPrevious
) {}

