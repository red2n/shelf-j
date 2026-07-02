# Run Flutter tests

Run the Flutter test suite for the storefront app. Pass an optional path to run a single file or directory.

```bash
cd frontends/shelf-app && /home/navin/flutter/bin/flutter test $ARGUMENTS --reporter compact
```

If `$ARGUMENTS` is empty this runs the full suite. Common usage:
- `/flutter-test` — full suite
- `/flutter-test test/features/storefront/` — storefront feature tests only
- `/flutter-test test/features/storefront/hide_price_checkout_test.dart` — single file

After the run, report: how many passed/failed, and for any failure quote the test name + first error line.
