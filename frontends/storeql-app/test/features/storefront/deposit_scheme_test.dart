import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// A deposit return scheme as the app reads it (09.16): which containers it
// takes back, and how it is said to a shopper.
// ---------------------------------------------------------------------------

void main() {
  final uk = DepositScheme.fromJson({
    'scope': 'GB',
    'currency': 'GBP',
    'depositEach': 0.2,
    'materials': ['PET', 'ALUMINIUM', 'STEEL'],
    'minVolumeMl': 150,
    'maxVolumeMl': 3000,
    'vatTreatment': 'OUTSIDE_SCOPE',
    'citation': 'SI 2025/67',
    'summary': 'A deposit on drinks containers.',
  });

  test('covers a container by material and volume, both or neither', () {
    expect(uk.covers('PET', 500), isTrue);
    expect(uk.covers('STEEL', 3000), isTrue);
    expect(uk.covers('GLASS', 500), isFalse, reason: 'glass is outside the UK scheme');
    expect(uk.covers('PET', 100), isFalse, reason: 'below the band');
    expect(uk.covers('PET', 5000), isFalse, reason: 'above the band');
    expect(uk.covers(null, 500), isFalse);
    expect(uk.covers('PET', null), isFalse);
  });

  test('says its materials and band in words', () {
    expect(uk.inWords, 'PET, aluminium or steel, 150 ml to 3 l');
    final one = DepositScheme.fromJson({
      'scope': 'X',
      'currency': 'EUR',
      'depositEach': 0.25,
      'materials': ['GLASS'],
      'minVolumeMl': 100,
      'maxVolumeMl': 750,
    });
    expect(one.inWords, 'glass, 100 ml to 750 ml');
  });

  test('a config without a scheme has none; one with it parses it', () {
    expect(const StorefrontConfig(showPrices: true).depositScheme, isNull);
    final config = StorefrontConfig(showPrices: true, depositScheme: uk);
    expect(config.depositScheme!.depositEach, 0.2);
    expect(config.depositScheme!.vatTreatment, 'OUTSIDE_SCOPE');
  });
}
