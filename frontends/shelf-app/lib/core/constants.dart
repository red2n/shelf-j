class ApiConstants {
  static const String baseUrl = 'http://localhost:8090/api';

  // service-name segments (must match gateway's routable-services list)
  static const String iam = 'iam-svc';
  static const String tenant = 'tenant-svc';
  static const String product = 'product-svc';
  static const String inventory = 'inventory-svc';
  static const String pricing = 'pricing-svc';
  static const String order = 'order-svc';
  static const String payment = 'payment-svc';
  static const String purchase = 'purchase-svc';
  static const String customer = 'customer-svc';
  static const String reporting = 'reporting-svc';
  static const String notification = 'notification-svc';
}

class StorageKeys {
  static const String accessToken = 'access_token';
  static const String refreshToken = 'refresh_token';

  /// Device-local history of orders placed from the (guest) storefront.
  static const String storefrontOrders = 'sf_orders';
}

class UserRoles {
  static const String owner = 'OWNER';
  static const String storeAdmin = 'STORE_ADMIN';
  static const String cashier = 'CASHIER';
  static const String customer = 'CUSTOMER';
}
