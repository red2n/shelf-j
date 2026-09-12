package com.shelfj.order.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.shelfj.ids.Ids;
import com.shelfj.order.domain.Domain.OrderItem;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A stored line without a sticker maps to a response without one, not to a "null" string. */
class OrderItemMarkdownMappingTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID VARIANT = Ids.newId();
  private static final UUID MARKDOWN = Ids.newId();

  @Test
  void theLineResponseCarriesTheMarkdownOnlyWhenThereIsOne() {
    OrderItem plain =
        new OrderItem(
            Ids.newId(),
            TENANT,
            Ids.newId(),
            VARIANT,
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null);
    assertNull(Mappers.toDto(plain).markdownId());
    OrderItem stickered =
        new OrderItem(
            Ids.newId(),
            TENANT,
            Ids.newId(),
            VARIANT,
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null,
            BigDecimal.ZERO,
            null,
            MARKDOWN);
    assertEquals(MARKDOWN.toString(), Mappers.toDto(stickered).markdownId());
  }
}
