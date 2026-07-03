class ApiConstants {
  /// Gateway base URL. Overridable at build time for a dockerized/remote deploy:
  ///   flutter build web --dart-define=SHELFJ_API_BASE=https://api.example.com/api
  /// Defaults to the local docker gateway (host port 8090).
  static const String baseUrl = String.fromEnvironment(
    'SHELFJ_API_BASE',
    defaultValue: 'http://localhost:8090/api',
  );

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

  // Customer data-collection keys
  static const String sfGender = 'sf_gender';
  static const String sfGenderAsked = 'sf_gender_asked';
  static const String sfPrefs = 'sf_cust_prefs';
  static const String sfPrefsAsked = 'sf_prefs_asked';
  static const String sfSurveys = 'sf_surveys';
  static const String sfSurveyLastDate = 'sf_survey_last_date';
  static const String sfFeedback = 'sf_feedback';

  /// Last delivery address used at checkout — prefills the form on the next order.
  static const String sfSavedAddress = 'sf_saved_address';
}

class UserRoles {
  static const String owner = 'OWNER';
  static const String storeAdmin = 'STORE_ADMIN';
  static const String cashier = 'CASHIER';
  static const String customer = 'CUSTOMER';
}
