import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// Non-web half of the file seam: a native build keeps the file in the app's
/// documents folder and says where (09.12, the "native save" a receipt or an
/// export lacked). The web half downloads instead and returns null.
Future<String?> saveTextFile(String filename, String content,
    {String mimeType = 'text/plain'}) async {
  final dir = await getApplicationDocumentsDirectory();
  final safe = filename.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
  final file = File('${dir.path}${Platform.pathSeparator}$safe');
  await file.writeAsString(content, flush: true);
  return file.path;
}

/// Fire-and-forget save, for callers that only ever wanted the file out of
/// the app. Failures are logged, never thrown into a button handler.
void downloadTextFile(String filename, String content, {String mimeType = 'text/plain'}) {
  saveTextFile(filename, content, mimeType: mimeType).then(
    (path) => debugPrint('Saved $filename to $path'),
    onError: (Object e) => debugPrint('Could not save $filename: $e'),
  );
}
