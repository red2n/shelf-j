/// This till's receipt printer (09.12).
///
/// A printer is hardware on one till, so its settings live on the device, not
/// on the server: the same shop's second till may have a different printer or
/// none. The web build prints through the browser or a print bridge on the
/// LAN; the desktop and mobile builds talk to a network printer directly or
/// save the receipt as a file.
library;

import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/constants.dart';
import '../../core/storage/app_storage.dart';
import 'pos_receipt_escpos.dart';

/// How this till produces a receipt.
enum PrinterMode {
  /// The browser's print dialog over the rendered HTML. Web only.
  browser,

  /// ESC/POS over a raw socket to a network printer (port 9100). Not from a
  /// browser, which cannot open a socket.
  network,

  /// ESC/POS posted to a print bridge on the LAN, which hands the bytes to the
  /// printer. Works everywhere, including the web.
  bridge,

  /// The receipt kept as a file: downloaded on the web, written to the
  /// documents folder on a native build.
  save,

  /// No paper receipt from this till.
  none;

  static PrinterMode fromName(String? name) =>
      PrinterMode.values.firstWhere((m) => m.name == name, orElse: () => defaultMode);

  /// What a till does before anyone configures it: what it always did.
  static PrinterMode get defaultMode => kIsWeb ? PrinterMode.browser : PrinterMode.none;

  /// The modes this build can actually perform.
  static List<PrinterMode> get available => kIsWeb
      ? const [PrinterMode.browser, PrinterMode.bridge, PrinterMode.save, PrinterMode.none]
      : const [PrinterMode.network, PrinterMode.bridge, PrinterMode.save, PrinterMode.none];

  String get label => switch (this) {
        PrinterMode.browser => 'Browser print dialog',
        PrinterMode.network => 'Network receipt printer (ESC/POS)',
        PrinterMode.bridge => 'Print bridge on this network (ESC/POS)',
        PrinterMode.save => 'Save the receipt as a file',
        PrinterMode.none => 'No paper receipt',
      };

  /// Whether the receipt is produced as ESC/POS bytes.
  bool get thermal => this == PrinterMode.network || this == PrinterMode.bridge;
}

class PrinterSettings {
  final PrinterMode mode;
  final String host;
  final int port;
  final String bridgeUrl;
  final PaperWidth paper;
  final bool openDrawer;

  const PrinterSettings({
    required this.mode,
    this.host = '',
    this.port = 9100,
    this.bridgeUrl = 'http://localhost:9109/print',
    this.paper = PaperWidth.mm80,
    this.openDrawer = false,
  });

  factory PrinterSettings.defaults() => PrinterSettings(mode: PrinterMode.defaultMode);

  /// Tolerant of whatever is on the device: a value of the wrong shape is the
  /// default for that field, never a crash on the till's first screen.
  factory PrinterSettings.fromJson(Map<String, dynamic> j) {
    final port = j['port'];
    return PrinterSettings(
      mode: PrinterMode.fromName(j['mode'] is String ? j['mode'] as String : null),
      host: j['host'] is String ? j['host'] as String : '',
      port: port is num ? port.toInt() : (int.tryParse('$port') ?? 9100),
      bridgeUrl: j['bridgeUrl'] is String ? j['bridgeUrl'] as String : 'http://localhost:9109/print',
      paper: PaperWidth.fromName(j['paper'] is String ? j['paper'] as String : null),
      openDrawer: j['openDrawer'] == true,
    );
  }

  Map<String, dynamic> toJson() => {
        'mode': mode.name,
        'host': host,
        'port': port,
        'bridgeUrl': bridgeUrl,
        'paper': paper.name,
        'openDrawer': openDrawer,
      };

  PrinterSettings copyWith({
    PrinterMode? mode,
    String? host,
    int? port,
    String? bridgeUrl,
    PaperWidth? paper,
    bool? openDrawer,
  }) =>
      PrinterSettings(
        mode: mode ?? this.mode,
        host: host ?? this.host,
        port: port ?? this.port,
        bridgeUrl: bridgeUrl ?? this.bridgeUrl,
        paper: paper ?? this.paper,
        openDrawer: openDrawer ?? this.openDrawer,
      );

  /// What is wrong with these settings, in words; empty when nothing is.
  List<String> validate() {
    final problems = <String>[];
    if (!PrinterMode.available.contains(mode)) {
      problems.add('${mode.label} is not available on this build.');
    }
    if (mode == PrinterMode.network) {
      if (host.trim().isEmpty || !_hostShape.hasMatch(host.trim())) {
        problems.add('The printer needs a host name or IP address.');
      }
      if (port < 1 || port > 65535) problems.add('The port must be between 1 and 65535.');
    }
    if (mode == PrinterMode.bridge) {
      final u = Uri.tryParse(bridgeUrl.trim());
      if (u == null || !(u.scheme == 'http' || u.scheme == 'https') || u.host.isEmpty) {
        problems.add('The print bridge needs an http or https address.');
      }
    }
    return problems;
  }

  static final _hostShape = RegExp(r'^[A-Za-z0-9.\-:\[\]]+$');
}

/// The till's printer settings, kept on the device.
class PrinterSettingsNotifier extends StateNotifier<PrinterSettings> {
  PrinterSettingsNotifier({AppStorage storage = const AppStorage()})
      : _storage = storage,
        super(PrinterSettings.defaults()) {
    _load();
  }

  final AppStorage _storage;

  Future<void> _load() async {
    try {
      final raw = await _storage.read(key: StorageKeys.posPrinter);
      if (raw == null || raw.isEmpty || !mounted) return;
      state = PrinterSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      // Unreadable settings are the defaults, not a broken till.
    }
  }

  /// Keeps the settings when they are valid; returns the problems otherwise.
  Future<List<String>> save(PrinterSettings s) async {
    final problems = s.validate();
    if (problems.isNotEmpty) return problems;
    state = s;
    try {
      await _storage.write(key: StorageKeys.posPrinter, value: jsonEncode(s.toJson()));
    } catch (_) {
      // Best effort: the till keeps the settings for this session either way.
    }
    return const [];
  }
}

final printerSettingsProvider =
    StateNotifierProvider<PrinterSettingsNotifier, PrinterSettings>(
  (ref) => PrinterSettingsNotifier(),
);
