import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// The storefront banner (03.12): a promotion is advertised as a reduction only
// when pricing-svc says it may be — never on a missing or garbled answer.
// ---------------------------------------------------------------------------

void main() {
  test('only promotions pricing-svc calls advertisable reach the banner', () {
    final all = [
      StorePromotion.fromJson({'name': 'Proven', 'type': 'PERCENT', 'value': 20, 'reductionAnnounceable': true}),
      StorePromotion.fromJson({'name': 'Too soon', 'type': 'PERCENT', 'value': 20, 'reductionAnnounceable': false}),
      StorePromotion.fromJson({'name': 'Old answer', 'type': 'PERCENT', 'value': 20}),
      StorePromotion.fromJson({'name': 'Garbled', 'type': 'FLAT', 'value': 5, 'reductionAnnounceable': 'yes'}),
      StorePromotion.fromJson({'name': 'Spend and save', 'type': 'BASKET_PERCENT', 'value': 10, 'reductionAnnounceable': true}),
    ];
    expect(advertisedPromotions(all).map((p) => p.name), ['Proven', 'Spend and save']);
    expect(advertisedPromotions(const []), isEmpty);
  });
}
