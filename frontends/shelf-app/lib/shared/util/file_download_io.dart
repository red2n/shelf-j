import 'package:flutter/foundation.dart';

/// Non-web stub.
///
/// Saving a generated file is a browser gesture — blob plus a synthetic anchor
/// click — with no analogue on Android or iOS, which the app also declares as
/// targets. A native build would share the bytes or write to the documents
/// directory instead; that is separate work.
///
/// This exists so those builds get nothing happening rather than a crash: the
/// previous code reached `package:web` from the top of `reports_screen.dart`, so
/// merely opening Admin → Reports would have failed to build for mobile.
void downloadTextFile(String filename, String content, {String mimeType = 'text/plain'}) {
  debugPrint('Download of $filename (${content.length} chars) is web-only on this build.');
}
