package com.shelfj.payment.settlement;

import com.shelfj.payment.domain.Settlements.ParsedFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;

/**
 * The settlement file layouts this service reads. A new acquirer is a new {@link
 * SettlementFileParser} bean and nothing else: nothing here or above it names a layout.
 */
@ApplicationScoped
public class SettlementFiles {

  @Inject Instance<SettlementFileParser> parsers;

  /** The layouts' names, in order. */
  public List<String> formats() {
    return all().stream().map(SettlementFileParser::format).sorted().toList();
  }

  /**
   * @throws SettlementFileException {@code SETTLEMENT_FORMAT_UNKNOWN} when no parser has the name,
   *     or whatever the parser finds wrong with the file
   */
  public ParsedFile parse(String format, String content) {
    String wanted = format == null ? "" : format.strip().toUpperCase(Locale.ROOT);
    return all().stream()
        .filter(p -> p.format().equals(wanted))
        .findFirst()
        .orElseThrow(
            () ->
                new SettlementFileException(
                    "SETTLEMENT_FORMAT_UNKNOWN",
                    "Not a layout this service reads: one of " + String.join(", ", formats())))
        .parse(content);
  }

  private List<SettlementFileParser> all() {
    return StreamSupport.stream(parsers.spliterator(), false).toList();
  }
}
