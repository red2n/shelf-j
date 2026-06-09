package com.shelfj.purchase.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {
  @Override
  public String serviceName() {
    return "purchase-svc";
  }

  @Override
  public int servicePort() {
    return 8009;
  }

  @Override
  public String dbSchema() {
    return "purchase";
  }
}
