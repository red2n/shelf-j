import 'dart:js_interop';

// ignore: avoid_web_libraries_in_flutter
import 'package:web/web.dart' as web;

/// Web half of [saveTextFile]: a browser saves a file by downloading it, so there is no path to
/// report and this returns null. Without it the web build did not compile at all (SJ-D58).
Future<String?> saveTextFile(String filename, String content,
    {String mimeType = 'text/plain'}) async {
  downloadTextFile(filename, content, mimeType: mimeType);
  return null;
}

/// Web: wrap the text in a blob and click a synthetic anchor at it, which is how
/// a browser is asked to save a file it was not served.
void downloadTextFile(String filename, String content, {String mimeType = 'text/plain'}) {
  final blob = web.Blob(
    [content.toJS].toJS,
    web.BlobPropertyBag(type: mimeType),
  );
  final url = web.URL.createObjectURL(blob);
  final anchor = web.HTMLAnchorElement()
    ..href = url
    ..download = filename;
  web.document.body?.append(anchor);
  anchor.click();
  anchor.remove();
  web.URL.revokeObjectURL(url);
}
