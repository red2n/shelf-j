import 'dart:convert';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import 'providers/admin_providers.dart';

class BulkImportScreen extends ConsumerStatefulWidget {
  const BulkImportScreen({super.key});

  @override
  ConsumerState<BulkImportScreen> createState() => _BulkImportScreenState();
}

class _BulkImportScreenState extends ConsumerState<BulkImportScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this);
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TabBar(
          controller: _tabs,
          tabAlignment: TabAlignment.start,
          isScrollable: true,
          tabs: const [
            Tab(icon: Icon(Icons.inventory_2_outlined), text: 'Catalog Import'),
            Tab(icon: Icon(Icons.move_to_inbox_outlined), text: 'Stock Receive'),
          ],
        ),
        Expanded(
          child: TabBarView(
            controller: _tabs,
            children: const [
              _CatalogImportTab(),
              _StockReceiveTab(),
            ],
          ),
        ),
      ],
    );
  }
}

// ── Tab 1: Catalog (categories + products) ────────────────────────────────────

const _catalogJsonSample = '''{
  "categories": [
    { "name": "Beverages" },
    { "name": "Cold Drinks", "parentName": "Beverages" }
  ],
  "products": [
    {
      "name": "Mango Juice 1L",
      "categoryName": "Cold Drinks",
      "sellableOnline": true,
      "sellablePos": true,
      "variants": [
        { "sku": "MJ-1L-001", "barcode": "8901234567890", "unit": "PCS" }
      ]
    }
  ]
}''';

const _catalogCsvSample =
    'name,category,sku,barcode,unit,sellable_online,sellable_pos\n'
    'Mango Juice 1L,Cold Drinks,MJ-1L-001,8901234567890,PCS,true,true\n'
    'Orange Juice 500ml,Cold Drinks,OJ-500-001,,PCS,true,true\n';

class _CatalogImportTab extends ConsumerStatefulWidget {
  const _CatalogImportTab();

  @override
  ConsumerState<_CatalogImportTab> createState() => _CatalogImportTabState();
}

class _CatalogImportTabState extends ConsumerState<_CatalogImportTab> {
  final _jsonCtrl = TextEditingController(text: _catalogJsonSample);
  bool _useCsv = false;
  String? _csvFileName;
  String? _csvContent;
  bool _loading = false;
  Map<String, dynamic>? _result;
  String? _error;

  @override
  void dispose() {
    _jsonCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickCsv() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['csv'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    final file = result.files.first;
    final bytes = file.bytes;
    if (bytes == null) return;
    setState(() {
      _csvFileName = file.name;
      _csvContent = utf8.decode(bytes);
      _result = null;
      _error = null;
    });
  }

  Map<String, dynamic> _csvToPayload(String csv) {
    final lines = csv
        .split('\n')
        .map((l) => l.trim())
        .where((l) => l.isNotEmpty)
        .toList();
    if (lines.length < 2) throw Exception('CSV must have a header row and at least one data row.');

    final headers = lines[0].split(',').map((h) => h.trim().toLowerCase()).toList();
    final nameIdx = headers.indexOf('name');
    final catIdx = headers.indexOf('category');
    final skuIdx = headers.indexOf('sku');
    final barcodeIdx = headers.indexOf('barcode');
    final unitIdx = headers.indexOf('unit');
    final onlineIdx = headers.indexOf('sellable_online');
    final posIdx = headers.indexOf('sellable_pos');

    if (nameIdx < 0 || skuIdx < 0) {
      throw Exception('CSV must have at least "name" and "sku" columns.');
    }

    final products = <Map<String, dynamic>>[];
    for (var i = 1; i < lines.length; i++) {
      final cols = lines[i].split(',').map((c) => c.trim()).toList();
      String _col(int idx) => idx >= 0 && idx < cols.length ? cols[idx] : '';

      final name = _col(nameIdx);
      if (name.isEmpty) continue;
      final sku = _col(skuIdx);

      final variant = <String, dynamic>{'sku': sku};
      final barcode = _col(barcodeIdx);
      if (barcode.isNotEmpty) variant['barcode'] = barcode;
      final unit = _col(unitIdx);
      if (unit.isNotEmpty) variant['unit'] = unit;

      final product = <String, dynamic>{
        'name': name,
        'variants': [variant],
        'sellableOnline': _col(onlineIdx).toLowerCase() != 'false',
        'sellablePos': _col(posIdx).toLowerCase() != 'false',
      };
      final cat = _col(catIdx);
      if (cat.isNotEmpty) product['categoryName'] = cat;

      products.add(product);
    }
    return {'categories': [], 'products': products};
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Result / error banners
          if (_result != null) ...[
            _ResultBanner(result: _result!),
            const SizedBox(height: 16),
          ],
          if (_error != null) ...[
            _ErrorBanner(message: _error!),
            const SizedBox(height: 16),
          ],

          // Mode toggle
          SegmentedButton<bool>(
            segments: const [
              ButtonSegment(
                value: false,
                icon: Icon(Icons.code),
                label: Text('JSON Editor'),
              ),
              ButtonSegment(
                value: true,
                icon: Icon(Icons.upload_file),
                label: Text('Upload CSV'),
              ),
            ],
            selected: {_useCsv},
            onSelectionChanged: (s) => setState(() {
              _useCsv = s.first;
              _result = null;
              _error = null;
            }),
          ),
          const SizedBox(height: 16),

          if (!_useCsv) ...[
            // ── JSON mode ──
            Card(
              color: cs.surfaceContainerHigh,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Text(
                  '• categories: list of { name, parentName? }\n'
                  '• products: list of { name, categoryName?, sellableOnline?, sellablePos?, variants: [{sku, barcode?, unit?}] }\n'
                  '• Existing categories are reused by name. Duplicate SKUs are skipped.',
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
              ),
            ),
            const SizedBox(height: 12),
            Container(
              decoration: BoxDecoration(
                border: Border.all(color: cs.outline.withAlpha(80)),
                borderRadius: BorderRadius.circular(8),
              ),
              child: TextField(
                controller: _jsonCtrl,
                maxLines: 20,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                decoration: const InputDecoration(
                  border: InputBorder.none,
                  contentPadding: EdgeInsets.all(12),
                ),
              ),
            ),
            const SizedBox(height: 16),
            OverflowBar(
              spacing: 8,
              overflowSpacing: 8,
              children: [
                FilledButton.icon(
                  onPressed: _loading ? null : _submitJson,
                  icon: _loading
                      ? const SizedBox(
                          height: 16,
                          width: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white))
                      : const Icon(Icons.upload),
                  label: const Text('Run Import'),
                ),
                OutlinedButton.icon(
                  onPressed: () => setState(() {
                    _jsonCtrl.text = _catalogJsonSample;
                    _result = null;
                    _error = null;
                  }),
                  icon: const Icon(Icons.restart_alt),
                  label: const Text('Reset to Sample'),
                ),
              ],
            ),
          ] else ...[
            // ── CSV mode ──
            Card(
              color: cs.surfaceContainerHigh,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.info_outline, size: 16, color: cs.primary),
                        const SizedBox(width: 6),
                        Text('CSV format',
                            style: TextStyle(
                                fontWeight: FontWeight.bold, color: cs.primary)),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Required columns: name, sku\n'
                      'Optional columns: category, barcode, unit, sellable_online, sellable_pos\n'
                      'One row = one product with one variant. Duplicate SKUs are skipped.',
                      style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            // File drop zone
            InkWell(
              onTap: _pickCsv,
              borderRadius: BorderRadius.circular(12),
              child: Container(
                padding: const EdgeInsets.all(32),
                decoration: BoxDecoration(
                  border: Border.all(
                    color: _csvFileName != null
                        ? cs.primary
                        : cs.outline.withAlpha(120),
                    width: 1.5,
                    style: BorderStyle.solid,
                  ),
                  borderRadius: BorderRadius.circular(12),
                  color: _csvFileName != null
                      ? cs.primaryContainer.withAlpha(60)
                      : cs.surfaceContainerLow,
                ),
                child: Column(
                  children: [
                    Icon(
                      _csvFileName != null
                          ? Icons.description
                          : Icons.upload_file_outlined,
                      size: 40,
                      color: _csvFileName != null
                          ? cs.primary
                          : cs.outlineVariant,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      _csvFileName ?? 'Click to select a CSV file',
                      style: TextStyle(
                        fontWeight: _csvFileName != null
                            ? FontWeight.bold
                            : FontWeight.normal,
                        color: _csvFileName != null ? cs.primary : cs.outline,
                      ),
                    ),
                    if (_csvFileName == null)
                      Text(
                        'Accepts .csv files only',
                        style:
                            TextStyle(fontSize: 12, color: cs.outlineVariant),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            OverflowBar(
              spacing: 8,
              overflowSpacing: 8,
              children: [
                FilledButton.icon(
                  onPressed:
                      (_loading || _csvContent == null) ? null : _submitCsv,
                  icon: _loading
                      ? const SizedBox(
                          height: 16,
                          width: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white))
                      : const Icon(Icons.upload),
                  label: const Text('Run Import'),
                ),
                OutlinedButton.icon(
                  onPressed: _pickCsv,
                  icon: const Icon(Icons.folder_open),
                  label: const Text('Choose File'),
                ),
                TextButton.icon(
                  onPressed: _downloadSampleCsv,
                  icon: const Icon(Icons.download),
                  label: const Text('Download Sample'),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  void _downloadSampleCsv() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Sample CSV'),
        content: SelectableText(
          _catalogCsvSample,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  Future<void> _submitJson() async {
    setState(() {
      _loading = true;
      _result = null;
      _error = null;
    });
    Map<String, dynamic> payload;
    try {
      payload = json.decode(_jsonCtrl.text) as Map<String, dynamic>;
    } catch (e) {
      setState(() {
        _loading = false;
        _error = 'Invalid JSON: $e';
      });
      return;
    }
    await _post(payload);
  }

  Future<void> _submitCsv() async {
    setState(() {
      _loading = true;
      _result = null;
      _error = null;
    });
    Map<String, dynamic> payload;
    try {
      payload = _csvToPayload(_csvContent!);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = e.toString();
      });
      return;
    }
    await _post(payload);
  }

  Future<void> _post(Map<String, dynamic> payload) async {
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.product}/admin/import',
            data: payload,
          );
      final data = resp.data['data'] as Map<String, dynamic>? ?? {};
      setState(() {
        _loading = false;
        _result = data;
      });
      ref.invalidate(productsProvider);
      ref.invalidate(categoriesProvider);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }
}

// ── Tab 2: Stock receive ──────────────────────────────────────────────────────

const _stockCsvSample =
    'variant_id,store_id,qty,cost_price,batch_no,expiry_date\n'
    '<variant-uuid>,<store-uuid>,100,5.50,BATCH-001,2025-12-31\n'
    '<variant-uuid>,<store-uuid>,50,,BATCH-002,\n';

class _CsvRow {
  final int rowNum;
  final String variantId;
  final String storeId;
  final double qty;
  final double? costPrice;
  final String? batchNo;
  final String? expiryDate;

  const _CsvRow({
    required this.rowNum,
    required this.variantId,
    required this.storeId,
    required this.qty,
    this.costPrice,
    this.batchNo,
    this.expiryDate,
  });
}

class _RowResult {
  final int rowNum;
  final bool success;
  final String? error;
  const _RowResult(this.rowNum, {required this.success, this.error});
}

class _StockReceiveTab extends ConsumerStatefulWidget {
  const _StockReceiveTab();

  @override
  ConsumerState<_StockReceiveTab> createState() => _StockReceiveTabState();
}

class _StockReceiveTabState extends ConsumerState<_StockReceiveTab> {
  // single-item form
  final _formKey = GlobalKey<FormState>();
  final _variantIdCtrl = TextEditingController();
  final _qtyCtrl = TextEditingController();
  final _batchCtrl = TextEditingController();
  final _costCtrl = TextEditingController();
  final _expiryCtrl = TextEditingController();
  String? _storeId;
  bool _loading = false;
  String? _formError;
  String? _formSuccess;

  // CSV upload
  bool _useCsv = false;
  String? _csvFileName;
  List<_CsvRow>? _parsedRows;
  String? _csvParseError;
  bool _csvLoading = false;
  int _csvDone = 0;
  List<_RowResult> _csvResults = [];

  @override
  void dispose() {
    _variantIdCtrl.dispose();
    _qtyCtrl.dispose();
    _batchCtrl.dispose();
    _costCtrl.dispose();
    _expiryCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickCsv() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['csv'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    final file = result.files.first;
    final bytes = file.bytes;
    if (bytes == null) return;
    final content = utf8.decode(bytes);

    // Parse immediately on pick
    try {
      final rows = _parseCsv(content);
      setState(() {
        _csvFileName = file.name;
        _parsedRows = rows;
        _csvParseError = null;
        _csvResults = [];
        _csvDone = 0;
      });
    } catch (e) {
      setState(() {
        _csvFileName = file.name;
        _parsedRows = null;
        _csvParseError = e.toString();
      });
    }
  }

  List<_CsvRow> _parseCsv(String content) {
    final lines = content
        .split('\n')
        .map((l) => l.trim())
        .where((l) => l.isNotEmpty)
        .toList();
    if (lines.length < 2) throw Exception('CSV must have a header and at least one data row.');

    final headers = lines[0].split(',').map((h) => h.trim().toLowerCase()).toList();
    final varIdx = headers.indexOf('variant_id');
    final storeIdx = headers.indexOf('store_id');
    final qtyIdx = headers.indexOf('qty');
    final costIdx = headers.indexOf('cost_price');
    final batchIdx = headers.indexOf('batch_no');
    final expiryIdx = headers.indexOf('expiry_date');

    if (varIdx < 0) throw Exception('Missing required column: variant_id');
    if (storeIdx < 0) throw Exception('Missing required column: store_id');
    if (qtyIdx < 0) throw Exception('Missing required column: qty');

    final rows = <_CsvRow>[];
    for (var i = 1; i < lines.length; i++) {
      final cols = lines[i].split(',').map((c) => c.trim()).toList();
      String col(int idx) => idx >= 0 && idx < cols.length ? cols[idx] : '';

      final variantId = col(varIdx);
      final storeId = col(storeIdx);
      final qtyStr = col(qtyIdx);

      if (variantId.isEmpty || storeId.isEmpty || qtyStr.isEmpty) continue;

      final qty = double.tryParse(qtyStr);
      if (qty == null || qty <= 0) {
        throw Exception('Row ${i + 1}: qty "$qtyStr" is not a valid positive number.');
      }

      final costStr = col(costIdx);
      final cost = costStr.isNotEmpty ? double.tryParse(costStr) : null;
      if (costStr.isNotEmpty && cost == null) {
        throw Exception('Row ${i + 1}: cost_price "$costStr" is not a valid number.');
      }

      final expiry = col(expiryIdx);
      rows.add(_CsvRow(
        rowNum: i + 1,
        variantId: variantId,
        storeId: storeId,
        qty: qty,
        costPrice: cost,
        batchNo: col(batchIdx).isNotEmpty ? col(batchIdx) : null,
        expiryDate: expiry.isNotEmpty ? expiry : null,
      ));
    }
    if (rows.isEmpty) throw Exception('No valid data rows found in CSV.');
    return rows;
  }

  Future<void> _submitCsv() async {
    if (_parsedRows == null) return;
    setState(() {
      _csvLoading = true;
      _csvResults = [];
      _csvDone = 0;
    });

    final dio = ref.read(apiClientProvider).dio;
    final results = <_RowResult>[];

    for (final row in _parsedRows!) {
      try {
        await dio.post(
          '/${ApiConstants.inventory}/admin/inventory/receive',
          data: {
            'storeId': row.storeId,
            'variantId': row.variantId,
            'qty': row.qty,
            if (row.costPrice != null) 'costPrice': row.costPrice,
            if (row.batchNo != null) 'batchNo': row.batchNo,
            if (row.expiryDate != null) 'expiryDate': row.expiryDate,
          },
        );
        results.add(_RowResult(row.rowNum, success: true));
      } catch (e) {
        results.add(_RowResult(row.rowNum, success: false, error: e.toString()));
      }
      setState(() {
        _csvDone++;
        _csvResults = List.from(results);
      });
    }

    setState(() => _csvLoading = false);
  }

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    final cs = Theme.of(context).colorScheme;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Mode toggle
            SegmentedButton<bool>(
              segments: const [
                ButtonSegment(
                  value: false,
                  icon: Icon(Icons.edit_note),
                  label: Text('Single Item'),
                ),
                ButtonSegment(
                  value: true,
                  icon: Icon(Icons.upload_file),
                  label: Text('Bulk CSV'),
                ),
              ],
              selected: {_useCsv},
              onSelectionChanged: (s) => setState(() {
                _useCsv = s.first;
              }),
            ),
            const SizedBox(height: 24),

            if (!_useCsv) ...[
              // ── Single-item form (unchanged) ──
              if (_formSuccess != null) ...[
                _SuccessBanner(message: _formSuccess!),
                const SizedBox(height: 16),
              ],
              if (_formError != null) ...[
                _ErrorBanner(message: _formError!),
                const SizedBox(height: 16),
              ],
              Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    storesAsync.when(
                      loading: () => const LinearProgressIndicator(),
                      error: (_, __) => const Text('Could not load stores'),
                      data: (stores) => DropdownButtonFormField<String>(
                        value: _storeId,
                        decoration: const InputDecoration(labelText: 'Store *'),
                        items: stores
                            .where((s) => s.status.toUpperCase() == 'ACTIVE')
                            .map((s) => DropdownMenuItem(
                                value: s.id,
                                child: Text('${s.name} (${s.code})')))
                            .toList(),
                        onChanged: (v) => setState(() => _storeId = v),
                        validator: (v) => v == null ? 'Required' : null,
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _variantIdCtrl,
                      decoration: const InputDecoration(
                        labelText: 'Variant ID *',
                        hintText: 'UUID of the product variant',
                        prefixIcon: Icon(Icons.label_outline),
                      ),
                      validator: (v) =>
                          v == null || v.trim().isEmpty ? 'Required' : null,
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        Expanded(
                          child: TextFormField(
                            controller: _qtyCtrl,
                            keyboardType: TextInputType.number,
                            decoration: const InputDecoration(
                              labelText: 'Quantity *',
                              prefixIcon: Icon(Icons.numbers),
                            ),
                            validator: (v) {
                              if (v == null || v.trim().isEmpty) return 'Required';
                              if (double.tryParse(v) == null || double.parse(v) <= 0) {
                                return 'Must be > 0';
                              }
                              return null;
                            },
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: TextFormField(
                            controller: _costCtrl,
                            keyboardType: TextInputType.number,
                            decoration: const InputDecoration(
                              labelText: 'Cost price',
                              prefixIcon: Icon(Icons.currency_rupee),
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        Expanded(
                          child: TextFormField(
                            controller: _batchCtrl,
                            decoration: const InputDecoration(
                              labelText: 'Batch no.',
                              hintText: 'e.g. BATCH-2024-01',
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: TextFormField(
                            controller: _expiryCtrl,
                            decoration: const InputDecoration(
                              labelText: 'Expiry date',
                              hintText: 'YYYY-MM-DD',
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                    FilledButton.icon(
                      onPressed: _loading ? null : _submitForm,
                      icon: _loading
                          ? const SizedBox(
                              height: 16,
                              width: 16,
                              child: CircularProgressIndicator(
                                  strokeWidth: 2, color: Colors.white))
                          : const Icon(Icons.move_to_inbox_outlined),
                      label: const Text('Receive Stock'),
                    ),
                  ],
                ),
              ),
            ] else ...[
              // ── CSV bulk upload ──
              Card(
                color: cs.surfaceContainerHigh,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(Icons.info_outline, size: 16, color: cs.primary),
                          const SizedBox(width: 6),
                          Text('CSV columns',
                              style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  color: cs.primary)),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Required: variant_id, store_id, qty\n'
                        'Optional: cost_price, batch_no, expiry_date (YYYY-MM-DD)\n'
                        'Each row = one stock receive entry.',
                        style:
                            TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),

              // File pick zone
              InkWell(
                onTap: _csvLoading ? null : _pickCsv,
                borderRadius: BorderRadius.circular(12),
                child: Container(
                  padding: const EdgeInsets.all(32),
                  decoration: BoxDecoration(
                    border: Border.all(
                      color: _csvFileName != null
                          ? cs.primary
                          : cs.outline.withAlpha(120),
                      width: 1.5,
                    ),
                    borderRadius: BorderRadius.circular(12),
                    color: _csvFileName != null
                        ? cs.primaryContainer.withAlpha(60)
                        : cs.surfaceContainerLow,
                  ),
                  child: Column(
                    children: [
                      Icon(
                        _csvFileName != null
                            ? Icons.description
                            : Icons.upload_file_outlined,
                        size: 40,
                        color:
                            _csvFileName != null ? cs.primary : cs.outlineVariant,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _csvFileName ?? 'Click to select a CSV file',
                        style: TextStyle(
                          fontWeight: _csvFileName != null
                              ? FontWeight.bold
                              : FontWeight.normal,
                          color: _csvFileName != null ? cs.primary : cs.outline,
                        ),
                      ),
                      if (_parsedRows != null)
                        Text(
                          '${_parsedRows!.length} rows ready to submit',
                          style: TextStyle(
                              fontSize: 12, color: cs.primary),
                        ),
                    ],
                  ),
                ),
              ),

              if (_csvParseError != null) ...[
                const SizedBox(height: 12),
                _ErrorBanner(message: _csvParseError!),
              ],

              const SizedBox(height: 16),
              OverflowBar(
                spacing: 8,
                overflowSpacing: 8,
                children: [
                  FilledButton.icon(
                    onPressed:
                        (_csvLoading || _parsedRows == null) ? null : _submitCsv,
                    icon: _csvLoading
                        ? const SizedBox(
                            height: 16,
                            width: 16,
                            child: CircularProgressIndicator(
                                strokeWidth: 2, color: Colors.white))
                        : const Icon(Icons.move_to_inbox_outlined),
                    label: const Text('Receive Stock'),
                  ),
                  OutlinedButton.icon(
                    onPressed: _csvLoading ? null : _pickCsv,
                    icon: const Icon(Icons.folder_open),
                    label: const Text('Choose File'),
                  ),
                  TextButton.icon(
                    onPressed: () => showDialog(
                      context: context,
                      builder: (ctx) => AlertDialog(
                        title: const Text('Sample CSV'),
                        content: SelectableText(
                          _stockCsvSample,
                          style: const TextStyle(
                              fontFamily: 'monospace', fontSize: 12),
                        ),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(ctx),
                            child: const Text('Close'),
                          ),
                        ],
                      ),
                    ),
                    icon: const Icon(Icons.download),
                    label: const Text('View Sample'),
                  ),
                ],
              ),

              // Progress + results
              if (_csvLoading || _csvResults.isNotEmpty) ...[
                const SizedBox(height: 24),
                if (_csvLoading && _parsedRows != null)
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                          'Processing $_csvDone / ${_parsedRows!.length} rows…',
                          style: TextStyle(color: cs.outline)),
                      const SizedBox(height: 8),
                      LinearProgressIndicator(
                        value: _parsedRows!.isEmpty
                            ? null
                            : _csvDone / _parsedRows!.length,
                      ),
                    ],
                  ),
                if (_csvResults.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  _CsvResultsTable(results: _csvResults),
                ],
              ],
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _submitForm() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _formError = null;
      _formSuccess = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.inventory}/admin/inventory/receive',
        data: {
          'storeId': _storeId,
          'variantId': _variantIdCtrl.text.trim(),
          'qty': double.parse(_qtyCtrl.text.trim()),
          if (_batchCtrl.text.trim().isNotEmpty)
            'batchNo': _batchCtrl.text.trim(),
          if (_costCtrl.text.trim().isNotEmpty)
            'costPrice': double.parse(_costCtrl.text.trim()),
          if (_expiryCtrl.text.trim().isNotEmpty)
            'expiryDate': _expiryCtrl.text.trim(),
        },
      );
      _qtyCtrl.clear();
      _batchCtrl.clear();
      _costCtrl.clear();
      _expiryCtrl.clear();
      setState(() {
        _loading = false;
        _formSuccess = 'Stock received successfully.';
      });
    } catch (e) {
      setState(() {
        _loading = false;
        _formError = e.toString();
      });
    }
  }
}

// ── Shared result widgets ─────────────────────────────────────────────────────

class _CsvResultsTable extends StatelessWidget {
  final List<_RowResult> results;
  const _CsvResultsTable({required this.results});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final ok = results.where((r) => r.success).length;
    final failed = results.where((r) => !r.success).length;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            if (ok > 0)
              Chip(
                avatar: Icon(Icons.check_circle,
                    color: Colors.green.shade700, size: 16),
                label: Text('$ok succeeded'),
                backgroundColor: Colors.green.shade50,
              ),
            if (ok > 0 && failed > 0) const SizedBox(width: 8),
            if (failed > 0)
              Chip(
                avatar:
                    Icon(Icons.error_outline, color: cs.error, size: 16),
                label: Text('$failed failed'),
                backgroundColor: cs.errorContainer,
              ),
          ],
        ),
        if (failed > 0) ...[
          const SizedBox(height: 8),
          ...results.where((r) => !r.success).map((r) => Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.circle, size: 6, color: cs.error),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Row ${r.rowNum}: ${r.error ?? 'Unknown error'}',
                        style: TextStyle(
                            fontSize: 12, color: cs.onErrorContainer),
                      ),
                    ),
                  ],
                ),
              )),
        ],
      ],
    );
  }
}

class _ResultBanner extends StatelessWidget {
  final Map<String, dynamic> result;
  const _ResultBanner({required this.result});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final errors =
        (result['errors'] as List?)?.cast<Map<String, dynamic>>() ?? [];

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: errors.isEmpty
            ? Colors.green.shade50
            : cs.errorContainer.withAlpha(120),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
            color: errors.isEmpty ? Colors.green.shade200 : cs.error),
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
                color: errors.isEmpty ? Colors.green.shade700 : cs.error,
              ),
              const SizedBox(width: 8),
              Text(
                errors.isEmpty
                    ? 'Import completed'
                    : 'Import completed with errors',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  color: errors.isEmpty ? Colors.green.shade800 : cs.error,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 16,
            runSpacing: 4,
            children: [
              _Stat('Categories created', result['categoriesCreated']),
              _Stat('Categories skipped', result['categoriesSkipped']),
              _Stat('Products created', result['productsCreated']),
              _Stat('Variants created', result['variantsCreated']),
            ],
          ),
          if (errors.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('Errors:',
                style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: cs.error,
                    fontSize: 13)),
            const SizedBox(height: 4),
            ...errors.map((e) => Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.circle, size: 6, color: cs.error),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          '${e['item']}: ${e['reason']}',
                          style: TextStyle(
                              fontSize: 12, color: cs.onErrorContainer),
                        ),
                      ),
                    ],
                  ),
                )),
          ],
        ],
      ),
    );
  }
}

class _SuccessBanner extends StatelessWidget {
  final String message;
  const _SuccessBanner({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.green.shade50,
        border: Border.all(color: Colors.green.shade200),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(Icons.check_circle_outline, color: Colors.green.shade700),
          const SizedBox(width: 8),
          Text(message, style: TextStyle(color: Colors.green.shade800)),
        ],
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  final String message;
  const _ErrorBanner({required this.message});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.errorContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, color: cs.onErrorContainer),
          const SizedBox(width: 8),
          Expanded(
            child: Text(message, style: TextStyle(color: cs.onErrorContainer)),
          ),
        ],
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  final String label;
  final dynamic value;
  const _Stat(this.label, this.value);

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          '${value ?? 0}',
          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
        ),
        const SizedBox(width: 4),
        Text(label,
            style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.outline)),
      ],
    );
  }
}
