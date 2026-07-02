---
name: flutter-storefront-rules
description: Flutter/Riverpod rules specific to the Shelf-J storefront. Read before touching any file in frontends/shelf-app/lib/features/storefront/.
metadata:
  type: project
---

# Shelf-J Storefront — Flutter/Riverpod rules

## The showPrices invariant (most important rule)

`storefrontShowPricesProvider` controls whether the store is in **priced mode** or **catalog mode** (stock-only). When `false`, no price, currency, or amount must appear in any widget.

- **Default must be `false`** while config loads — not `true`. Defaulting to `true` causes a race window where prices are resolved and stored in `CartLine.unitPrice` before the config arrives.
- **Both `storefrontConfigProvider` and `storefrontShowPricesProvider` must NOT be `autoDispose`.** They are re-fetched on every navigation if autoDispose, causing price flashes.

## Provider lifecycle summary

| Provider | autoDispose? | Why |
|---|---|---|
| `storefrontConfigProvider` | No | Survives navigation; avoids race window |
| `storefrontShowPricesProvider` | No | Derived from config; same reason |
| `storefrontProductsProvider` | Yes (family) | Per-query cache is fine |
| `storefrontAvailabilityProvider` | Yes | Per-store snapshot |
| `cartProvider` | No | Cart must survive navigation |
| `storefrontOrdersProvider` | No | Device-local history |

## Checkout rules

1. `currency = rawCurrency.isNotEmpty ? rawCurrency : 'GBP'` — catalog mode items have `currency = ''`; never send that to the API.
2. `payNow = showPrices && _payNow` — catalog mode never captures payment.
3. Delivery address validation failure must show a SnackBar — not return silently.
4. Success dialog must never show price amounts when `showPrices = false`.

## Widget test conventions

- Override `storefrontConfigProvider` to inject `StorefrontConfig(showPrices: ...)` synchronously.
- Seed cart via `ProviderScope.containerOf(tester.element(find.byType(StorefrontCartScreen)))`.
- Set `tester.view.physicalSize = const Size(800, 1200)` when the checkout panel has many form fields (default 800×600 clips the button).
- Use `tester.ensureVisible()` before tapping off-screen buttons.
- Never tap the checkout button without overriding `storefrontDioProvider` — the real Dio creates pending timers.
- Tests live in `test/features/storefront/`.
