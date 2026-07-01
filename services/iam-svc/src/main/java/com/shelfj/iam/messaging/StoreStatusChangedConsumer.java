package com.shelfj.iam.messaging;

import com.shelfj.service.BaseStoreStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class StoreStatusChangedConsumer extends BaseStoreStatusChangedConsumer {

  @Override
  protected String consumerName() {
    return "iam-store-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-store-status";
  }
}
