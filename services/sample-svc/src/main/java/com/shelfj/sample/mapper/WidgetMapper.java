package com.shelfj.sample.mapper;

import com.shelfj.sample.domain.Widget;
import com.shelfj.sample.dto.WidgetResponse;

/** Entity ↔ DTO conversion (keeps the API contract decoupled from the table). */
public final class WidgetMapper {

    private WidgetMapper() {}

    public static WidgetResponse toResponse(Widget w) {
        return new WidgetResponse(w.id(), w.name(), w.createdAt());
    }
}
