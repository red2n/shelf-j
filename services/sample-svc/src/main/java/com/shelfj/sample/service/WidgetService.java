package com.shelfj.sample.service;

import com.shelfj.sample.domain.Widget;
import com.shelfj.sample.repo.WidgetRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for widgets. The "brain" — all rules live here, not in the resource (golden rule
 * #9). Every method takes the tenantId (sourced from context by the resource) and never trusts
 * caller-supplied tenant.
 */
@ApplicationScoped
public class WidgetService {

  private static final int MAX_NAME_LENGTH = 120;

  @Inject WidgetRepository repository;

  public Widget create(UUID tenantId, String name) {
    if (name == null || name.isBlank()) {
      throw ApiException.badRequest("WIDGET_NAME_REQUIRED", "name must not be blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw ApiException.badRequest("WIDGET_NAME_TOO_LONG", "name must be <= 120 chars");
    }
    return repository.insert(tenantId, name.trim());
  }

  public Widget get(UUID tenantId, UUID id) {
    return repository
        .findById(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("WIDGET_NOT_FOUND", "No widget with that id"));
  }

  public List<Widget> list(UUID tenantId, int limit) {
    return repository.list(tenantId, limit);
  }
}
