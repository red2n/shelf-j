import 'dart:convert';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import 'providers/admin_providers.dart';

// ── Supplier Catalogue CSV import ─────────────────────────────────────────────
//
// Accepts the GTBJ catalogue format:
//   Product ID, Category, Product Description, Quantity Type, Case Size, Price,
//   Quantity, Favourite[, Store]
//
// A single import creates categories, products AND store-availability in one go.

class BulkImportScreen extends ConsumerStatefulWidget {
  const BulkImportScreen({super.key});

  @override
  ConsumerState<BulkImportScreen> createState() => _BulkImportScreenState();
}

class _BulkImportScreenState extends ConsumerState<BulkImportScreen> {
  String? _fileName;
  String? _csvContent;
  String _mode = 'ADD';

  // Parsed preview stats (computed after file pick)
  int _rowCount = 0;
  int _productCount = 0;
  Set<String> _categoryNames = {};
  Set<String> _storeNamesInCsv = {};

  // Per-store-name override selected by the user (store name → store UUID)
  final Map<String, String?> _storeMapping = {};

  bool _loading = false;
  String? _error;
  Map<String, dynamic>? _result;

  // ── CSV parsing ─────────────────────────────────────────────────────────────

  List<String> _splitRow(String line) {
    final out = <String>[];
    final sb = StringBuffer();
    bool inQ = false;
    int pos = 0;
    while (pos < line.length) {
      final ch = line[pos];
      if (ch == '"') {
        if (inQ && pos + 1 < line.length && line[pos + 1] == '"') {
          sb.write('"');
          pos += 2;
        } else {
          inQ = !inQ;
          pos++;
        }
      } else if (ch == ',' && !inQ) {
        out.add(sb.toString().trim());
        sb.clear();
        pos++;
      } else {
        sb.write(ch);
        pos++;
      }
    }
    out.add(sb.toString().trim());
    return out;
  }

  int _headerIdx(List<String> headers, List<String> names) {
    for (final n in names) {
      final i = headers.indexWhere((h) => h.toLowerCase() == n.toLowerCase());
      if (i >= 0) return i;
    }
    return -1;
  }

  void _parseCsv(String csv) {
    final lines =
        csv.split(RegExp(r'\r?\n')).where((l) => l.trim().isNotEmpty).toList();
    if (lines.length < 2) {
      setState(() => _error = 'File must have a header row and at least one data row.');
      return;
    }
    // _headerIdx returns the first match — duplicate column names use the first occurrence.
    final headers = _splitRow(lines[0]);
    final idxDesc = _headerIdx(headers, [
      'product description', 'description', 'product name', 'name',
    ]);
    final idxCat = _headerIdx(headers, ['category']);
    final idxStore = _headerIdx(headers, ['store', 'store name', 'store_name']);

    if (idxDesc < 0) {
      setState(() => _error =
          'Could not find a product name column. '
          'Expected: "Product Description" (or "Description" / "Name").');
      return;
    }

    final products = <String>{};
    final cats = <String>{};
    final storeNames = <String>{};
    int rows = 0;

    for (var i = 1; i < lines.length; i++) {
      final cols = _splitRow(lines[i]);
      final desc = idxDesc < cols.length ? cols[idxDesc] : '';
      if (desc.isEmpty) continue;
      rows++;
      products.add(desc);
      if (idxCat >= 0 && idxCat < cols.length && cols[idxCat].isNotEmpty) {
        cats.add(cols[idxCat]);
      }
      if (idxStore >= 0 &&
          idxStore < cols.length &&
          cols[idxStore].isNotEmpty) {
        storeNames.add(cols[idxStore]);
      }
    }

    setState(() {
      _rowCount = rows;
      _productCount = products.length;
      _categoryNames = cats;
      _storeNamesInCsv = storeNames;
      _error = null;
      _result = null;
      // Initialise mapping slots for any new store names.
      for (final sn in storeNames) {
        _storeMapping.putIfAbsent(sn, () => null);
      }
    });
  }

  // ── File pick ────────────────────────────────────────────────────────────────

  Future<void> _pickFile() async {
    final r = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['csv'],
      withData: true,
    );
    if (r == null || r.files.isEmpty) return;
    final f = r.files.first;
    if (f.bytes == null) return;
    final csv = utf8.decode(f.bytes!);
    setState(() {
      _fileName = f.name;
      _csvContent = csv;
    });
    _parseCsv(csv);
  }

  // ── Import ───────────────────────────────────────────────────────────────────

  Future<void> _import(List<StoreInfo> stores) async {
    if (_csvContent == null) return;

    // Build store-name → UUID map from the user's selections + fallback to
    // exact-name match against the loaded store list.
    final storeNameToId = <String, String>{};
    for (final sn in _storeNamesInCsv) {
      final selected = _storeMapping[sn];
      if (selected != null && selected.isNotEmpty) {
        storeNameToId[sn] = selected;
      } else {
        // Exact name match (case-insensitive)
        final match = stores.where(
            (s) => s.name.toLowerCase() == sn.toLowerCase());
        if (match.isNotEmpty) storeNameToId[sn] = match.first.id;
      }
    }

    setState(() {
      _loading = true;
      _error = null;
      _result = null;
    });

    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.product}/admin/import/supplier-csv',
        data: {
          'csv': _csvContent,
          'mode': _mode,
          if (storeNameToId.isNotEmpty) 'storeNameToId': storeNameToId,
        },
      );
      ref.invalidate(productsProvider);
      ref.invalidate(categoriesProvider);
      if (!mounted) return;
      setState(() {
        _loading = false;
        _result = resp.data['data'] as Map<String, dynamic>?;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Import failed: $e';
      });
    }
  }

  // ── UI ───────────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // ── Header ──────────────────────────────────────────────────────
            Text('Supplier Catalogue Import',
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            Text(
              'Upload your supplier catalogue CSV. '
              'Categories, products, and store availability are all created in one step.',
              style: TextStyle(color: cs.outline, fontSize: 13),
            ),
            const SizedBox(height: 20),

            // ── Mode + upload row ─────────────────────────────────────────
            Wrap(
              spacing: 16,
              runSpacing: 12,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(
                        value: 'ADD',
                        icon: Icon(Icons.add),
                        label: Text('New import')),
                    ButtonSegment(
                        value: 'REPLACE',
                        icon: Icon(Icons.sync),
                        label: Text('Override existing')),
                  ],
                  selected: {_mode},
                  onSelectionChanged: (s) =>
                      setState(() => _mode = s.first),
                ),
                FilledButton.icon(
                  onPressed: _pickFile,
                  icon: const Icon(Icons.upload_file),
                  label: Text(
                      _fileName == null ? 'Choose CSV file' : 'Replace file'),
                ),
                TextButton.icon(
                  onPressed: () => _showFormatHelp(context),
                  icon: const Icon(Icons.help_outline, size: 18),
                  label: const Text('Expected format'),
                ),
                if (_fileName != null)
                  Text(_fileName!, style: TextStyle(color: cs.outline)),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              _mode == 'REPLACE'
                  ? 'Override mode: re-imports a sheet, replacing existing products by SKU.'
                  : 'New import mode: creates products and categories. Duplicate SKUs are reported as errors.',
              style: TextStyle(color: cs.outline, fontSize: 12),
            ),

            // ── Error / result banners ─────────────────────────────────────
            if (_error != null) ...[
              const SizedBox(height: 16),
              _ErrorBanner(message: _error!),
            ],
            if (_result != null) ...[
              const SizedBox(height: 16),
              _ResultBanner(result: _result!),
            ],

            // ── Preview + store mapping ────────────────────────────────────
            if (_csvContent != null && _error == null) ...[
              const SizedBox(height: 20),
              _PreviewCard(
                rowCount: _rowCount,
                productCount: _productCount,
                categoryNames: _categoryNames,
                storeNamesInCsv: _storeNamesInCsv,
              ),

              // Store-name mapping (only if CSV has a Store column)
              if (_storeNamesInCsv.isNotEmpty) ...[
                const SizedBox(height: 16),
                storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (_, __) =>
                      const Text('Could not load stores — store mapping skipped.'),
                  data: (stores) => _StoreMappingCard(
                    storeNamesInCsv: _storeNamesInCsv,
                    stores: stores,
                    mapping: _storeMapping,
                    onChanged: (name, id) =>
                        setState(() => _storeMapping[name] = id),
                  ),
                ),
              ],

              const SizedBox(height: 20),
              storesAsync.when(
                loading: () => const SizedBox.shrink(),
                error: (_, __) => FilledButton.icon(
                  onPressed: _loading ? null : () => _import([]),
                  icon: _loading
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white))
                      : const Icon(Icons.cloud_upload_outlined),
                  label: Text(_loading
                      ? 'Importing…'
                      : 'Import $_productCount products'),
                ),
                data: (stores) => FilledButton.icon(
                  onPressed: _loading ? null : () => _import(stores),
                  icon: _loading
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white))
                      : const Icon(Icons.cloud_upload_outlined),
                  label: Text(_loading
                      ? 'Importing…'
                      : 'Import $_productCount products'),
                ),
              ),
            ],

            // ── Empty state ────────────────────────────────────────────────
            if (_csvContent == null) ...[
              const SizedBox(height: 40),
              Center(
                child: Column(
                  children: [
                    Icon(Icons.upload_file_outlined,
                        size: 72, color: cs.outlineVariant),
                    const SizedBox(height: 12),
                    const Text('No file selected'),
                    const SizedBox(height: 4),
                    Text(
                      'Columns: Product ID · Category · Product Description · Store · Quantity · Price\n'
                      'Columns can be in any order. Only Product Description is required.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: cs.outline, fontSize: 12),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  void _showFormatHelp(BuildContext context) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('CSV format'),
        content: SizedBox(
          width: 620,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Upload a CSV file with any of these 6 columns (in any order). '
                'Only the product name column is required.\n',
              ),
              Container(
                padding: const EdgeInsets.all(12),
                color: Theme.of(ctx).colorScheme.surfaceContainerHighest,
                child: const SelectableText(
                  'Product ID, Category, Product Description, Store, Quantity, Price\n'
                  '115669521, Alcohol, Tennents Bavarian Pilsner 4x440ml, Main Store, 6, 28.86\n'
                  '114600215, Sugar & Baking, Tate & Lyle Granulated Sugar 1kg, , 15, 13.50\n'
                  '"113341477","Condiments, Sauces","Heinz Ketchup 460g", Warehouse, 12, 18.00',
                  style: TextStyle(fontFamily: 'monospace', fontSize: 12),
                ),
              ),
              const SizedBox(height: 12),
              const Text(
                'Columns (case-insensitive, any order, duplicates → first wins):\n'
                '  • Product ID → SKU (auto-generated as IMP-N if missing)\n'
                '  • Category → creates the category if it doesn\'t exist\n'
                '  • Product Description → product name (required)\n'
                '  • Store / Store Name → restricts product to that store\n'
                '  • Quantity → stored as-is\n'
                '  • Price → stored as-is\n\n'
                'Values are not validated — everything is imported as-is. '
                'Blank product name rows are skipped. '
                'Fields containing commas must be double-quoted.',
                style: TextStyle(fontSize: 12),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        ],
      ),
    );
  }
}

// ── Preview card ──────────────────────────────────────────────────────────────

class _PreviewCard extends StatelessWidget {
  final int rowCount;
  final int productCount;
  final Set<String> categoryNames;
  final Set<String> storeNamesInCsv;

  const _PreviewCard({
    required this.rowCount,
    required this.productCount,
    required this.categoryNames,
    required this.storeNamesInCsv,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: cs.surfaceContainerHigh,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.check_circle_outline,
                    color: Colors.green.shade700, size: 20),
                const SizedBox(width: 8),
                Text('File parsed successfully',
                    style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: Colors.green.shade800)),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 24,
              runSpacing: 8,
              children: [
                _Stat('$rowCount', 'rows'),
                _Stat('$productCount', 'products'),
                _Stat('${categoryNames.length}', 'categories'),
                if (storeNamesInCsv.isNotEmpty)
                  _Stat('${storeNamesInCsv.length}', 'stores in CSV'),
              ],
            ),
            if (categoryNames.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(categoryNames.take(8).join(' · ') +
                  (categoryNames.length > 8
                      ? ' + ${categoryNames.length - 8} more'
                      : ''),
                  style: TextStyle(fontSize: 12, color: cs.outline)),
            ],
          ],
        ),
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  final String value;
  final String label;
  const _Stat(this.value, this.label);

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value,
            style: const TextStyle(
                fontSize: 22, fontWeight: FontWeight.bold)),
        Text(label,
            style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.outline)),
      ],
    );
  }
}

// ── Store-name mapping card ───────────────────────────────────────────────────

class _StoreMappingCard extends StatelessWidget {
  final Set<String> storeNamesInCsv;
  final List<StoreInfo> stores;
  final Map<String, String?> mapping;
  final void Function(String name, String? id) onChanged;

  const _StoreMappingCard({
    required this.storeNamesInCsv,
    required this.stores,
    required this.mapping,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: cs.surfaceContainerHigh,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.store_outlined, size: 18, color: cs.primary),
                const SizedBox(width: 6),
                Text('Store mapping',
                    style: TextStyle(
                        fontWeight: FontWeight.bold, color: cs.primary)),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Map store names from the CSV to stores in the system. '
              'Exact-name matches are pre-filled. Products without a store are available everywhere.',
              style: TextStyle(fontSize: 12, color: cs.outline),
            ),
            const SizedBox(height: 12),
            for (final sn in storeNamesInCsv) ...[
              Row(
                children: [
                  Expanded(
                    flex: 2,
                    child: Text(sn,
                        style: const TextStyle(fontWeight: FontWeight.w500)),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    flex: 3,
                    child: DropdownButtonFormField<String>(
                      value: mapping[sn],
                      decoration: const InputDecoration(
                          isDense: true,
                          contentPadding: EdgeInsets.symmetric(
                              horizontal: 12, vertical: 10)),
                      hint: const Text('— all stores —'),
                      items: [
                        const DropdownMenuItem(
                            value: null, child: Text('— all stores —')),
                        for (final s in stores)
                          DropdownMenuItem(
                              value: s.id,
                              child: Text('${s.name} (${s.code})')),
                      ],
                      onChanged: (v) => onChanged(sn, v),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
            ],
          ],
        ),
      ),
    );
  }
}

// ── Result banner ─────────────────────────────────────────────────────────────

class _ResultBanner extends StatelessWidget {
  final Map<String, dynamic> result;
  const _ResultBanner({required this.result});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final errors = (result['errors'] as List?)?.cast<Map<String, dynamic>>() ?? [];
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: errors.isEmpty ? Colors.green.shade50 : cs.errorContainer,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
            color: errors.isEmpty ? Colors.green.shade300 : cs.error),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                  errors.isEmpty
                      ? Icons.check_circle_outline
                      : Icons.warning_amber_outlined,
                  color: errors.isEmpty ? Colors.green.shade700 : cs.error),
              const SizedBox(width: 8),
              Text(
                errors.isEmpty
                    ? 'Import completed successfully'
                    : 'Import completed with errors',
                style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color:
                        errors.isEmpty ? Colors.green.shade800 : cs.error),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 20,
            runSpacing: 4,
            children: [
              _ResultStat('${result['categoriesCreated'] ?? 0}',
                  'categories created'),
              _ResultStat('${result['categoriesSkipped'] ?? 0}',
                  'categories existing'),
              _ResultStat(
                  '${result['productsCreated'] ?? 0}', 'products created'),
              _ResultStat(
                  '${result['variantsCreated'] ?? 0}', 'variants created'),
            ],
          ),
          if (errors.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('Errors:',
                style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                    color: cs.error)),
            const SizedBox(height: 4),
            ...errors.take(20).map((e) => Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text(
                    '• ${e['item']}: ${e['reason']}',
                    style:
                        TextStyle(fontSize: 12, color: cs.onErrorContainer),
                  ),
                )),
            if (errors.length > 20)
              Text('…and ${errors.length - 20} more errors',
                  style: TextStyle(fontSize: 12, color: cs.outline)),
          ],
        ],
      ),
    );
  }
}

class _ResultStat extends StatelessWidget {
  final String value;
  final String label;
  const _ResultStat(this.value, this.label);

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(value,
            style: const TextStyle(
                fontWeight: FontWeight.bold, fontSize: 16)),
        const SizedBox(width: 4),
        Text(label,
            style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.outline)),
      ],
    );
  }
}

// ── Error banner ──────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  final String message;
  const _ErrorBanner({required this.message});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
          color: cs.errorContainer, borderRadius: BorderRadius.circular(8)),
      child: Row(
        children: [
          Icon(Icons.error_outline, color: cs.onErrorContainer),
          const SizedBox(width: 8),
          Expanded(
              child: Text(message,
                  style: TextStyle(color: cs.onErrorContainer))),
        ],
      ),
    );
  }
}
