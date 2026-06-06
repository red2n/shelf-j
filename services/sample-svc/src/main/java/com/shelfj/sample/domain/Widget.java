package com.shelfj.sample.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Sample domain entity (a "widget"). Demonstrates a tenant-scoped table.
 * Real services map JPA entities here; the Phase-0 template uses a plain record + JDBC repo.
 */
public record Widget(
        UUID id,
        UUID tenantId,
        String name,
        Instant createdAt
) {}
