package com.storeql.iam.messaging;

import com.storeql.service.BaseKafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** {@code StaffRemoved} from tenant-svc: the role comes off the login (SJ-D51). */
@ApplicationScoped
class StaffRemovedConsumer extends BaseKafkaConsumer {

  @Inject StaffRemovedHandler handler;

  @Inject
  @ConfigProperty(
      name = "storeql.kafka.topics.staff-removed",
      defaultValue = "storeql.tenant.staff-removed")
  String topicCfg;

  @Override
  protected List<String> topics() {
    return List.of(topicCfg);
  }

  @Override
  protected String consumerName() {
    return "iam-staff-removed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-staff-removed";
  }

  @Override
  protected void handle(String topic, String value) {
    handler.handle(value);
  }
}
