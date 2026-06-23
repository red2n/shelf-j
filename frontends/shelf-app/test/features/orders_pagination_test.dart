import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/admin/providers/admin_providers.dart';
import 'package:shelf_app/features/admin/providers/orders_pagination.dart';

void main() {
  group('OrdersFilter', () {
    test('value equality + hashCode (so the family key is stable)', () {
      expect(const OrdersFilter('ALL', 'PENDING'),
          const OrdersFilter('ALL', 'PENDING'));
      expect(const OrdersFilter('ALL', 'PENDING').hashCode,
          const OrdersFilter('ALL', 'PENDING').hashCode);
      expect(const OrdersFilter('ALL', 'PENDING'),
          isNot(const OrdersFilter('POS', 'PENDING')));
    });
  });

  group('OrdersPage', () {
    test('hasMore reflects nextCursor', () {
      expect(const OrdersPage().hasMore, isFalse);
      expect(const OrdersPage(nextCursor: 'c').hasMore, isTrue);
    });

    test('copyWith keeps orders + cursor and toggles loadingMore', () {
      const page = OrdersPage(
        orders: <OrderSummary>[],
        nextCursor: 'c',
        isLoadingInitial: false,
      );
      final next = page.copyWith(isLoadingMore: true);
      expect(next.isLoadingMore, isTrue);
      expect(next.nextCursor, 'c');
      expect(next.hasMore, isTrue);
    });
  });
}
