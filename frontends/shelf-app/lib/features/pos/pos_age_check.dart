import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_error.dart';
import '../admin/providers/admin_providers.dart';
import 'pos_providers.dart';

// ---------------------------------------------------------------------------
// The till's age check.
//
// Selling an age-restricted item to someone under age is an offence in every
// market this platform trades in, and the defence is having taken "all
// reasonable precautions and exercised all due diligence". product-svc can say
// which items are restricted and from what age in which country; without a
// prompt at the till that knowledge protects nobody.
// ---------------------------------------------------------------------------

/// The answer to "may this item go into the sale without a check?".
///
/// Three outcomes, not two. [AgeCheckBlocked] is the one that matters: when the
/// till cannot find out whether an item is restricted — no country for the
/// store, no rule for that country, an answer it cannot read, a failed call —
/// the item is not sold. Treating "could not tell" as "not restricted" is how a
/// till sells alcohol to a child with every screen saying it asked.
sealed class AgeCheckResult {
  const AgeCheckResult();
}

class AgeCheckNotRestricted extends AgeCheckResult {
  const AgeCheckNotRestricted();
}

class AgeCheckRestricted extends AgeCheckResult {
  final String category;
  final int minimumAge;
  final String country;

  /// True when the age is the business's own policy rather than the legal
  /// minimum — product-svc lets a tenant be stricter, never laxer.
  final bool storePolicy;

  const AgeCheckRestricted({
    required this.category,
    required this.minimumAge,
    required this.country,
    required this.storePolicy,
  });
}

class AgeCheckBlocked extends AgeCheckResult {
  final String message;
  const AgeCheckBlocked(this.message);
}

/// Asks product-svc whether [variantId] is age-restricted in [country].
Future<AgeCheckResult> checkAgeRestriction(
    Dio dio, String variantId, String? country) async {
  final cc = country?.trim().toUpperCase();
  if (cc == null || cc.length != 2) {
    // Not guessed. The same bottle is 18 in the UK, 20 in Japan and 21 in the
    // US; a default country would be a wrong answer somewhere.
    return const AgeCheckBlocked(
        "This store has no country set, so age-restricted items can't be "
        'checked. Set the country in Admin → Stores.');
  }
  try {
    final resp = await dio.get(
      '/${ApiConstants.product}/catalog/variants/$variantId/age-check',
      queryParameters: {'country': cc},
    );
    final data = (resp.data as Map?)?['data'] as Map?;
    final restricted = data?['restricted'];
    // The explicit flag, never the absence of minimumAge: a null field is left
    // out of the JSON entirely, so "no age given" cannot be told apart from a
    // field that went missing. Anything but a real boolean is not an answer.
    if (restricted == false) return const AgeCheckNotRestricted();
    if (restricted != true) {
      return const AgeCheckBlocked(
          "Couldn't tell whether this item is age-restricted. Scan it again.");
    }
    final age = (data?['minimumAge'] as num?)?.toInt();
    final category = data?['category'] as String?;
    if (age == null || category == null) {
      return const AgeCheckBlocked(
          "This item is age-restricted but its minimum age couldn't be read. "
          'Scan it again.');
    }
    return AgeCheckRestricted(
      category: category,
      minimumAge: age,
      country: cc,
      storePolicy: data?['tenantOverride'] == true,
    );
  } on DioException catch (e) {
    if (apiErrorCode(e) == 'PRODUCT_NO_AGE_RULE') {
      return AgeCheckBlocked(
          'This item is age-restricted, but no minimum age is set for $cc. '
          "It can't be sold until one is.");
    }
    return const AgeCheckBlocked(
        "Couldn't check the age restriction. Scan it again.");
  }
}

/// The country whose age rules apply at this till: the store's own, falling
/// back to the tenant's. Null when neither is a real country code.
Future<String?> resolveSaleCountry(WidgetRef ref) async {
  final storeId = ref.read(posStoreProvider);
  final stores = ref.read(posStoresProvider).value;
  if (storeId != null && stores != null) {
    for (final s in stores) {
      final c = s.country?.trim();
      if (s.id == storeId && c != null && c.length == 2) return c;
    }
  }
  try {
    final tenant = await ref.read(tenantInfoProvider.future);
    final c = tenant.country.trim();
    return c.length == 2 ? c : null;
  } catch (_) {
    return null;
  }
}

/// The check a cashier must make before an age-restricted item is added.
///
/// Not dismissable by tapping outside it: the cashier either confirms they have
/// checked, or refuses the sale.
class AgeVerificationDialog extends StatelessWidget {
  final String itemName;
  final AgeCheckRestricted check;

  const AgeVerificationDialog(
      {super.key, required this.itemName, required this.check});

  static String categoryLabel(String category) => switch (category) {
        'ALCOHOL' => 'Alcohol',
        'TOBACCO' => 'Tobacco',
        'NICOTINE_VAPE' => 'Vapes and nicotine products',
        'KNIVES' => 'Knives and bladed articles',
        'CORROSIVES' => 'Corrosive substances',
        'SOLVENTS' => 'Solvents',
        'FIREWORKS' => 'Fireworks',
        'LOTTERY' => 'Lottery',
        'VIDEO_18' => 'Age-rated film or game',
        'PETROL' => 'Petrol',
        _ => category,
      };

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return AlertDialog(
      icon: Icon(Icons.badge_outlined, color: cs.error),
      title: const Text('Age-restricted item'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(itemName, style: const TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 2),
          Text(categoryLabel(check.category)),
          const SizedBox(height: 16),
          Text('The customer must be ${check.minimumAge} or over.',
              style: text.titleMedium),
          const SizedBox(height: 4),
          Text(check.storePolicy
              ? 'Store policy in ${check.country} — stricter than the legal minimum.'
              : 'Legal minimum in ${check.country}.'),
          const SizedBox(height: 12),
          const Text(
              "If you aren't sure, ask for photo ID. If they can't show it, "
              'refuse the sale.'),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Refuse sale'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: Text('Checked — ${check.minimumAge}+'),
        ),
      ],
    );
  }
}
