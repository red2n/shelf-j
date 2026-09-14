package com.shelfj.iam.messaging;

import com.shelfj.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** {@code RoleDefined} from tenant-svc: a custom role's permissions, applied to its holders. */
@ApplicationScoped
class RoleDefinedConsumer extends BaseKafkaConsumer {

  @Inject RoleDefinedHandler handler;

  @Inject
  @ConfigProperty(
      name = "shelfj.kafka.topics.role-defined",
      defaultValue = "shelfj.tenant.role-defined")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "iam-role-defined-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-role-defined";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
