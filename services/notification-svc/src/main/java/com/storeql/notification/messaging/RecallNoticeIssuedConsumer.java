package com.storeql.notification.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Polls issued recall notices and dispatches each to {@link RecallNoticeIssuedHandler}. */
@ApplicationScoped
class RecallNoticeIssuedConsumer extends BaseKafkaConsumer {

  @Inject RecallNoticeIssuedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.recall-notice-issued",
      defaultValue = "storeql.order.recall-notice-issued")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "notification-recall-notice-issued-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
