package com.shelfj.sample.dto;

/**
 * Request body to create a widget. DTOs are the API contract (golden rule #10) — never expose entities.
 * Note: there is NO tenantId field — tenant comes from the JWT/context (golden rule #3).
 */
public record CreateWidgetRequest(String name) {}
