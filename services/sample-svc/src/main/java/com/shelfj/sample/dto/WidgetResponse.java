package com.shelfj.sample.dto;

import java.time.Instant;
import java.util.UUID;

/** Response shape for a widget. */
public record WidgetResponse(UUID id, String name, Instant createdAt) {}
