import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// The item lifecycle as the admin console says it: four states in words, and
// the one move each state can make next (delisting is always its own action).
// ---------------------------------------------------------------------------

void main() {
  test('each state has its words, and an unknown one is shown as it came', () {
    expect(lifecycleLabelOf('NEW_LINE'), 'New line');
    expect(lifecycleLabelOf('active'), 'On sale');
    expect(lifecycleLabelOf('DISCONTINUED'), 'Discontinued');
    expect(lifecycleLabelOf('DELISTED'), 'Delisted');
    expect(lifecycleLabelOf('RETIRED'), 'RETIRED');
  });

  test('the next move follows the lifecycle, and a delisted line has none', () {
    expect(nextLifecycleMove('NEW_LINE')?.$1, 'launch');
    expect(nextLifecycleMove('ACTIVE')?.$1, 'discontinue');
    expect(nextLifecycleMove('DISCONTINUED')?.$1, 'reinstate');
    expect(nextLifecycleMove('DELISTED'), isNull);
  });

  test('a product carries its launch day and its state in words', () {
    final p = ProductInfo.fromJson({
      'id': 'p-1',
      'name': 'Spring cola',
      'status': 'NEW_LINE',
      'sellableOnline': true,
      'sellablePos': true,
      'createdAt': '2026-09-16T10:00:00Z',
      'launchOn': '2026-10-01',
    });
    expect(p.launchOn, '2026-10-01');
    expect(p.lifecycleLabel, 'New line');
  });
}
