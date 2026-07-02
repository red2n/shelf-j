# Flutter static analysis

Run `flutter analyze` on the storefront app and report any warnings or errors.

```bash
cd frontends/shelf-app && /home/navin/flutter/bin/flutter analyze --fatal-infos 2>&1
```

Summarise the output:
- If clean: confirm "No issues found."
- If there are issues: list each one with file:line and the error message. Group by severity (error → warning → info). Suggest the fix for any Shelf-J-specific violations (unused provider, missing `showPrices` guard, etc.).
