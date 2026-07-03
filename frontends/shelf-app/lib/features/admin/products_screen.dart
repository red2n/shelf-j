import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../shared/widgets/barcode_scanner_sheet.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import 'providers/products_pagination.dart';

class ProductsScreen extends ConsumerStatefulWidget {
  const ProductsScreen({super.key});

  @override
  ConsumerState<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends ConsumerState<ProductsScreen> {
  String _search = '';
  String? _categoryFilter;

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(productsPaginationProvider(_categoryFilter));
    final catsAsync = ref.watch(categoriesProvider);
    final cs = Theme.of(context).colorScheme;

    final cats = catsAsync.valueOrNull ?? [];
    final catById = {for (var c in cats) c.id: c};

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Header
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('Products',
                        style: Theme.of(context).textTheme.headlineMedium),
                  ),
                  if (!page.isLoadingInitial && page.error == null)
                    Chip(
                      // "+" signals more exist beyond what's loaded so far — page.products.length
                      // alone isn't the tenant's true total once results span more than one page.
                      label: Text(
                          '${page.products.length}${page.hasMore ? '+' : ''} products'),
                      backgroundColor: cs.secondaryContainer,
                    ),
                ],
              ),
              const SizedBox(height: 12),
              OverflowBar(
                spacing: 8,
                overflowSpacing: 8,
                overflowAlignment: OverflowBarAlignment.start,
                children: [
                  FilledButton.icon(
                    onPressed: () =>
                        _showCreateDialog(context, ref, cats),
                    icon: const Icon(Icons.add),
                    label: const Text('New Product'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () {
                      ref
                          .read(productsPaginationProvider(_categoryFilter).notifier)
                          .refresh();
                      ref.invalidate(categoriesProvider);
                    },
                    icon: const Icon(Icons.refresh),
                    label: const Text('Refresh'),
                  ),
                ],
              ),
            ],
          ),
        ),

        // Filters
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 12),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  decoration: const InputDecoration(
                    hintText: 'Search products…',
                    prefixIcon: Icon(Icons.search),
                    isDense: true,
                  ),
                  onChanged: (v) => setState(() => _search = v.trim()),
                ),
              ),
              const SizedBox(width: 12),
              if (cats.isNotEmpty)
                DropdownButton<String?>(
                  value: _categoryFilter,
                  hint: const Text('All categories'),
                  underline: const SizedBox.shrink(),
                  items: [
                    const DropdownMenuItem(value: null, child: Text('All categories')),
                    ...cats.where((c) => c.status.toUpperCase() == 'ACTIVE').map((c) =>
                        DropdownMenuItem(value: c.id, child: Text(c.name))),
                  ],
                  onChanged: (v) => setState(() => _categoryFilter = v),
                ),
            ],
          ),
        ),

        // List
        Expanded(
          child: Builder(builder: (context) {
            if (page.isLoadingInitial) {
              return const LoadingView(label: 'Loading products…');
            }
            if (page.error != null && page.products.isEmpty) {
              return ErrorView(
                message: 'Could not load products.',
                onRetry: () => ref
                    .read(productsPaginationProvider(_categoryFilter).notifier)
                    .refresh(),
              );
            }
            // The category filter is applied server-side (it's the pagination family key);
            // free-text search stays a client-side filter over whatever's loaded so far.
            final filtered = _search.isEmpty
                ? page.products
                : page.products
                    .where((p) =>
                        p.name.toLowerCase().contains(_search.toLowerCase()))
                    .toList();

            if (filtered.isEmpty) {
              return Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.inventory_2_outlined,
                        size: 64, color: cs.outlineVariant),
                    const SizedBox(height: 16),
                    Text(
                      page.products.isEmpty
                          ? 'No products yet'
                          : 'No products match the filter',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    if (page.products.isEmpty)
                      OutlinedButton.icon(
                        onPressed: () =>
                            _showCreateDialog(context, ref, cats),
                        icon: const Icon(Icons.add),
                        label: const Text('New Product'),
                      )
                    else
                      TextButton(
                        onPressed: () => setState(() {
                          _search = '';
                          _categoryFilter = null;
                        }),
                        child: const Text('Clear filter'),
                      ),
                  ],
                ),
              );
            }

            return Column(
              children: [
                Expanded(
                  child: LayoutBuilder(builder: (context, bc) {
                    final wide = bc.maxWidth >= 700;
                    if (wide) {
                      return _WideTable(
                        products: filtered,
                        catById: catById,
                        onViewVariants: (p) =>
                            _showVariantsDialog(context, ref, p),
                        onAssortment: (p) =>
                            _showAssortmentDialog(context, ref, p),
                        onImage: (p) => _manageImage(context, ref, p),
                        onDelist: (p) => _delist(context, ref, p),
                      );
                    }
                    return _NarrowList(
                      products: filtered,
                      catById: catById,
                      onViewVariants: (p) =>
                          _showVariantsDialog(context, ref, p),
                      onAssortment: (p) => _showAssortmentDialog(context, ref, p),
                      onImage: (p) => _manageImage(context, ref, p),
                      onDelist: (p) => _delist(context, ref, p),
                    );
                  }),
                ),
                if (page.hasMore || page.isLoadingMore)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: page.isLoadingMore
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : OutlinedButton(
                            onPressed: () => ref
                                .read(productsPaginationProvider(_categoryFilter)
                                    .notifier)
                                .loadMore(),
                            child: const Text('Load more'),
                          ),
                  ),
              ],
            );
          }),
        ),
      ],
    );
  }

  void _showCreateDialog(
      BuildContext context, WidgetRef ref, List<CategoryInfo> cats) {
    showDialog(
      context: context,
      builder: (_) => _ProductDialog(
        cats: cats,
        onSave: (data) async {
          await ref.read(apiClientProvider).dio.post(
                '/${ApiConstants.product}/admin/products',
                data: data,
              );
          ref.read(productsPaginationProvider(_categoryFilter).notifier).refresh();
        },
      ),
    );
  }

  void _showVariantsDialog(
      BuildContext context, WidgetRef ref, ProductInfo product) {
    showDialog(
      context: context,
      builder: (_) => _VariantsDialog(product: product),
    );
  }

  void _showAssortmentDialog(
      BuildContext context, WidgetRef ref, ProductInfo product) {
    showDialog(
      context: context,
      builder: (_) => _AssortmentDialog(product: product),
    );
  }

  /// Owner uploads (or removes) the product's storefront image. JPEG/PNG/WebP, max 512 KB —
  /// matching product-svc's PUT /admin/products/{id}/image contract.
  Future<void> _manageImage(
      BuildContext context, WidgetRef ref, ProductInfo product) async {
    final action = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Image for "${product.name}"'),
        content: const Text(
            'Upload a JPEG, PNG or WebP up to 512 KB. It appears on the '
            'storefront catalog and product page.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel')),
          OutlinedButton.icon(
            onPressed: () => Navigator.pop(ctx, 'remove'),
            icon: const Icon(Icons.delete_outline, size: 18),
            label: const Text('Remove image'),
          ),
          FilledButton.icon(
            onPressed: () => Navigator.pop(ctx, 'upload'),
            icon: const Icon(Icons.upload_outlined, size: 18),
            label: const Text('Choose file…'),
          ),
        ],
      ),
    );
    if (action == null || !context.mounted) return;

    final dio = ref.read(apiClientProvider).dio;
    try {
      if (action == 'remove') {
        await dio.delete(
            '/${ApiConstants.product}/admin/products/${product.id}/image');
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Product image removed.')));
        }
        return;
      }
      final picked = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'webp'],
        withData: true,
      );
      final file = picked?.files.firstOrNull;
      final bytes = file?.bytes;
      if (file == null || bytes == null) return;
      if (bytes.length > 512 * 1024) {
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(
                'Image is ${(bytes.length / 1024).round()} KB — max is 512 KB. '
                'Please resize it and try again.'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ));
        }
        return;
      }
      final ext = (file.extension ?? '').toLowerCase();
      final contentType = switch (ext) {
        'png' => 'image/png',
        'webp' => 'image/webp',
        _ => 'image/jpeg',
      };
      await dio.put(
        '/${ApiConstants.product}/admin/products/${product.id}/image',
        data: bytes,
        options: Options(contentType: contentType),
      );
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Product image uploaded.')));
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Image update failed: $e'),
          backgroundColor: Theme.of(context).colorScheme.error,
        ));
      }
    }
  }

  Future<void> _delist(
      BuildContext context, WidgetRef ref, ProductInfo product) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delist "${product.name}"?'),
        content:
            const Text('The product will be hidden from the storefront and POS.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
              foregroundColor: Theme.of(ctx).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delist'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .delete('/${ApiConstants.product}/admin/products/${product.id}');
      ref.read(productsPaginationProvider(_categoryFilter).notifier).refresh();
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Failed: $e'),
          backgroundColor: Theme.of(context).colorScheme.error,
        ));
      }
    }
  }
}

// ── Wide table ────────────────────────────────────────────────────────────────

class _WideTable extends StatelessWidget {
  final List<ProductInfo> products;
  final Map<String, CategoryInfo> catById;
  final void Function(ProductInfo) onViewVariants;
  final void Function(ProductInfo) onAssortment;
  final void Function(ProductInfo) onImage;
  final void Function(ProductInfo) onDelist;

  const _WideTable({
    required this.products,
    required this.catById,
    required this.onViewVariants,
    required this.onAssortment,
    required this.onImage,
    required this.onDelist,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, bc) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Card(
          clipBehavior: Clip.antiAlias,
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: ConstrainedBox(
              constraints: BoxConstraints(minWidth: bc.maxWidth - 32),
              child: DataTable(
                headingRowColor:
                    WidgetStatePropertyAll(cs.surfaceContainerHigh),
                columnSpacing: 24,
                columns: const [
                  DataColumn(label: Text('Product')),
                  DataColumn(label: Text('Category')),
                  DataColumn(label: Text('Online')),
                  DataColumn(label: Text('POS')),
                  DataColumn(label: Text('Status')),
                  DataColumn(label: Text('')),
                ],
                rows: products.map((p) {
                  final active = p.status.toUpperCase() == 'ACTIVE';
                  final catName = p.categoryId != null
                      ? (catById[p.categoryId]?.name ?? '—')
                      : '—';
                  return DataRow(cells: [
                    DataCell(Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(p.name,
                            style: const TextStyle(
                                fontWeight: FontWeight.bold)),
                        if (p.description != null && p.description!.isNotEmpty)
                          Text(
                            p.description!.length > 40
                                ? '${p.description!.substring(0, 40)}…'
                                : p.description!,
                            style: TextStyle(
                                fontSize: 11, color: cs.outline),
                          ),
                      ],
                    )),
                    DataCell(Text(catName,
                        style: TextStyle(
                            fontSize: 12, color: cs.outline))),
                    DataCell(Icon(
                      p.sellableOnline
                          ? Icons.check_circle_outline
                          : Icons.remove_circle_outline,
                      size: 18,
                      color:
                          p.sellableOnline ? Colors.green : cs.outlineVariant,
                    )),
                    DataCell(Icon(
                      p.sellablePos
                          ? Icons.check_circle_outline
                          : Icons.remove_circle_outline,
                      size: 18,
                      color:
                          p.sellablePos ? Colors.green : cs.outlineVariant,
                    )),
                    DataCell(_StatusChip(active: active, label: p.status)),
                    DataCell(PopupMenuButton<String>(
                      icon: const Icon(Icons.more_vert),
                      tooltip: 'Actions',
                      itemBuilder: (_) => [
                        const PopupMenuItem(
                            value: 'variants',
                            child: Row(children: [
                              Icon(Icons.view_list_outlined, size: 18),
                              SizedBox(width: 8),
                              Text('View Variants'),
                            ])),
                        const PopupMenuItem(
                            value: 'stores',
                            child: Row(children: [
                              Icon(Icons.storefront_outlined, size: 18),
                              SizedBox(width: 8),
                              Text('Sold at stores'),
                            ])),
                        const PopupMenuItem(
                            value: 'image',
                            child: Row(children: [
                              Icon(Icons.image_outlined, size: 18),
                              SizedBox(width: 8),
                              Text('Product image'),
                            ])),
                        if (active)
                          PopupMenuItem(
                              value: 'delist',
                              child: Row(children: [
                                Icon(Icons.block_outlined,
                                    size: 18, color: cs.error),
                                const SizedBox(width: 8),
                                Text('Delist',
                                    style: TextStyle(color: cs.error)),
                              ])),
                      ],
                      onSelected: (v) {
                        if (v == 'variants') {
                          onViewVariants(p);
                        } else if (v == 'stores') {
                          onAssortment(p);
                        } else if (v == 'image') {
                          onImage(p);
                        } else {
                          onDelist(p);
                        }
                      },
                    )),
                  ]);
                }).toList(),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// ── Narrow list ───────────────────────────────────────────────────────────────

class _NarrowList extends StatelessWidget {
  final List<ProductInfo> products;
  final Map<String, CategoryInfo> catById;
  final void Function(ProductInfo) onViewVariants;
  final void Function(ProductInfo) onAssortment;
  final void Function(ProductInfo) onImage;
  final void Function(ProductInfo) onDelist;

  const _NarrowList({
    required this.products,
    required this.catById,
    required this.onViewVariants,
    required this.onAssortment,
    required this.onImage,
    required this.onDelist,
  });

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: products.length,
      separatorBuilder: (_, __) => const SizedBox(height: 4),
      itemBuilder: (context, i) {
        final p = products[i];
        final cs = Theme.of(context).colorScheme;
        final active = p.status.toUpperCase() == 'ACTIVE';
        final catName =
            p.categoryId != null ? (catById[p.categoryId]?.name ?? '—') : '—';
        return Card(
          child: ListTile(
            leading: CircleAvatar(
              backgroundColor: cs.primaryContainer,
              child: Text(
                p.name.isNotEmpty ? p.name[0].toUpperCase() : '?',
                style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: cs.onPrimaryContainer),
              ),
            ),
            title: Text(p.name,
                style: const TextStyle(fontWeight: FontWeight.bold)),
            subtitle: Text(catName,
                style: TextStyle(fontSize: 12, color: cs.outline)),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _StatusChip(active: active, label: p.status),
                PopupMenuButton<String>(
                  icon: const Icon(Icons.more_vert),
                  itemBuilder: (_) => [
                    const PopupMenuItem(
                        value: 'variants',
                        child: Row(children: [
                          Icon(Icons.view_list_outlined, size: 18),
                          SizedBox(width: 8),
                          Text('View Variants'),
                        ])),
                    const PopupMenuItem(
                        value: 'stores',
                        child: Row(children: [
                          Icon(Icons.storefront_outlined, size: 18),
                          SizedBox(width: 8),
                          Text('Sold at stores'),
                        ])),
                    const PopupMenuItem(
                        value: 'image',
                        child: Row(children: [
                          Icon(Icons.image_outlined, size: 18),
                          SizedBox(width: 8),
                          Text('Product image'),
                        ])),
                    if (active)
                      PopupMenuItem(
                          value: 'delist',
                          child: Row(children: [
                            Icon(Icons.block_outlined,
                                size: 18, color: cs.error),
                            const SizedBox(width: 8),
                            Text('Delist',
                                style: TextStyle(color: cs.error)),
                          ])),
                  ],
                  onSelected: (v) {
                    if (v == 'variants') {
                      onViewVariants(p);
                    } else if (v == 'stores') {
                      onAssortment(p);
                    } else if (v == 'image') {
                      onImage(p);
                    } else {
                      onDelist(p);
                    }
                  },
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

// ── Status chip ───────────────────────────────────────────────────────────────

class _StatusChip extends StatelessWidget {
  final bool active;
  final String label;
  const _StatusChip({required this.active, required this.label});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: active ? Colors.green.shade100 : cs.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.bold,
            color: active ? Colors.green.shade800 : cs.onErrorContainer,
          )),
    );
  }
}

// ── Create product dialog ─────────────────────────────────────────────────────

class _ProductDialog extends StatefulWidget {
  final List<CategoryInfo> cats;
  final Future<void> Function(Map<String, dynamic> data) onSave;

  const _ProductDialog({required this.cats, required this.onSave});

  @override
  State<_ProductDialog> createState() => _ProductDialogState();
}

class _ProductDialogState extends State<_ProductDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  String? _categoryId;
  bool _online = true;
  bool _pos = true;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final activeCats =
        widget.cats.where((c) => c.status.toUpperCase() == 'ACTIVE').toList();

    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('New Product',
                      style: Theme.of(context)
                          .textTheme
                          .titleLarge
                          ?.copyWith(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 20),
                  if (_error != null) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(_error!,
                          style: TextStyle(color: cs.onErrorContainer)),
                    ),
                    const SizedBox(height: 12),
                  ],
                  TextFormField(
                    controller: _nameCtrl,
                    autofocus: true,
                    decoration: const InputDecoration(
                      labelText: 'Product name *',
                      prefixIcon: Icon(Icons.inventory_2_outlined),
                    ),
                    validator: (v) =>
                        v == null || v.trim().isEmpty ? 'Required' : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _descCtrl,
                    maxLines: 2,
                    decoration: const InputDecoration(
                      labelText: 'Description',
                      alignLabelWithHint: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String?>(
                    value: _categoryId,
                    decoration:
                        const InputDecoration(labelText: 'Category'),
                    items: [
                      const DropdownMenuItem(
                          value: null, child: Text('— None —')),
                      ...activeCats.map((c) =>
                          DropdownMenuItem(value: c.id, child: Text(c.name))),
                    ],
                    onChanged: (v) => setState(() => _categoryId = v),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        child: CheckboxListTile(
                          title: const Text('Online'),
                          value: _online,
                          onChanged: (v) =>
                              setState(() => _online = v ?? true),
                          contentPadding: EdgeInsets.zero,
                          controlAffinity: ListTileControlAffinity.leading,
                        ),
                      ),
                      Expanded(
                        child: CheckboxListTile(
                          title: const Text('POS'),
                          value: _pos,
                          onChanged: (v) =>
                              setState(() => _pos = v ?? true),
                          contentPadding: EdgeInsets.zero,
                          controlAffinity: ListTileControlAffinity.leading,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      TextButton(
                        onPressed:
                            _loading ? null : () => Navigator.pop(context),
                        child: const Text('Cancel'),
                      ),
                      const SizedBox(width: 8),
                      FilledButton(
                        onPressed: _loading ? null : _submit,
                        child: _loading
                            ? const SizedBox(
                                height: 18,
                                width: 18,
                                child: CircularProgressIndicator(
                                    strokeWidth: 2, color: Colors.white))
                            : const Text('Create'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await widget.onSave({
        'name': _nameCtrl.text.trim(),
        'description':
            _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim(),
        'categoryId': _categoryId,
        'sellableOnline': _online,
        'sellablePos': _pos,
      });
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }
}

// ── Variants dialog ───────────────────────────────────────────────────────────

class _VariantsDialog extends ConsumerStatefulWidget {
  final ProductInfo product;
  const _VariantsDialog({required this.product});

  @override
  ConsumerState<_VariantsDialog> createState() => _VariantsDialogState();
}

class _VariantsDialogState extends ConsumerState<_VariantsDialog> {
  bool _showAddForm = false;
  final _skuCtrl = TextEditingController();
  final _barcodeCtrl = TextEditingController();
  final _unitCtrl = TextEditingController();
  bool _saving = false;
  String? _saveError;

  @override
  void dispose() {
    _skuCtrl.dispose();
    _barcodeCtrl.dispose();
    _unitCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final variantsAsync =
        ref.watch(productVariantsProvider(widget.product.id));
    final pricesAsync = ref.watch(variantPricesProvider);
    final cs = Theme.of(context).colorScheme;

    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560, maxHeight: 600),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(widget.product.name,
                            style: Theme.of(context)
                                .textTheme
                                .titleLarge
                                ?.copyWith(fontWeight: FontWeight.bold)),
                        Text('Variants',
                            style: TextStyle(
                                color: cs.outline, fontSize: 13)),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
              const SizedBox(height: 16),

              // Variants list
              Expanded(
                child: variantsAsync.when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (e, _) =>
                      Center(child: Text('Error: $e')),
                  data: (variants) {
                    if (variants.isEmpty && !_showAddForm) {
                      return Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.label_outline,
                                size: 48, color: cs.outlineVariant),
                            const SizedBox(height: 12),
                            const Text('No variants yet'),
                            const SizedBox(height: 12),
                            FilledButton.icon(
                              onPressed: () =>
                                  setState(() => _showAddForm = true),
                              icon: const Icon(Icons.add),
                              label: const Text('Add Variant'),
                            ),
                          ],
                        ),
                      );
                    }
                    return ListView(
                      children: [
                        ...variants.map((v) {
                          final price = pricesAsync.valueOrNull?[v.id];
                          return Card(
                            child: ListTile(
                              leading: const Icon(Icons.label_outline),
                              title: Text(v.sku,
                                  style: const TextStyle(
                                      fontFamily: 'monospace',
                                      fontWeight: FontWeight.bold)),
                              subtitle: Row(
                                children: [
                                  _StatusChip(
                                    active: v.status.toUpperCase() == 'ACTIVE',
                                    label: v.status,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      [
                                        if (v.barcode != null)
                                          'EAN: ${v.barcode}',
                                        if (v.unit != null) v.unit!,
                                      ].join('  ·  '),
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(fontSize: 12),
                                    ),
                                  ),
                                ],
                              ),
                              trailing: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text(
                                    price != null
                                        ? price.toStringAsFixed(2)
                                        : 'No price',
                                    style: TextStyle(
                                      fontWeight: FontWeight.bold,
                                      color: price != null
                                          ? cs.primary
                                          : cs.outline,
                                    ),
                                  ),
                                  IconButton(
                                    icon: const Icon(Icons.price_change_outlined),
                                    tooltip: 'Set price',
                                    onPressed: () => _setPrice(v, price),
                                  ),
                                ],
                              ),
                            ),
                          );
                        }),
                        if (!_showAddForm)
                          Center(
                            child: Padding(
                              padding:
                                  const EdgeInsets.symmetric(vertical: 8),
                              child: OutlinedButton.icon(
                                onPressed: () =>
                                    setState(() => _showAddForm = true),
                                icon: const Icon(Icons.add),
                                label: const Text('Add Variant'),
                              ),
                            ),
                          ),
                      ],
                    );
                  },
                ),
              ),

              // Add variant inline form
              if (_showAddForm) ...[
                const Divider(),
                const SizedBox(height: 8),
                Text('Add Variant',
                    style: Theme.of(context)
                        .textTheme
                        .titleSmall
                        ?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                if (_saveError != null) ...[
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(_saveError!,
                        style:
                            TextStyle(color: cs.onErrorContainer, fontSize: 12)),
                  ),
                  const SizedBox(height: 8),
                ],
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _skuCtrl,
                        decoration: const InputDecoration(
                          labelText: 'SKU *',
                          isDense: true,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        controller: _barcodeCtrl,
                        decoration: InputDecoration(
                          labelText: 'Barcode',
                          isDense: true,
                          suffixIcon: IconButton(
                            icon: const Icon(Icons.camera_alt_outlined,
                                size: 18),
                            tooltip: 'Scan barcode',
                            onPressed: () async {
                              final code = await scanBarcodeWithCamera(context);
                              if (code != null && code.isNotEmpty) {
                                _barcodeCtrl.text = code;
                              }
                            },
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                      width: 80,
                      child: TextField(
                        controller: _unitCtrl,
                        decoration: const InputDecoration(
                          labelText: 'Unit',
                          isDense: true,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: _saving
                          ? null
                          : () => setState(() {
                                _showAddForm = false;
                                _saveError = null;
                              }),
                      child: const Text('Cancel'),
                    ),
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: _saving ? null : _saveVariant,
                      child: _saving
                          ? const SizedBox(
                              height: 16,
                              width: 16,
                              child: CircularProgressIndicator(
                                  strokeWidth: 2, color: Colors.white))
                          : const Text('Add'),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _setPrice(VariantInfo v, double? current) async {
    final ctrl =
        TextEditingController(text: current != null ? current.toStringAsFixed(2) : '');
    String? error;
    bool saving = false;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: Text('Set price — ${v.sku}'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (error != null) ...[
                Text(error!,
                    style: TextStyle(color: Theme.of(ctx).colorScheme.error)),
                const SizedBox(height: 8),
              ],
              TextField(
                controller: ctrl,
                autofocus: true,
                keyboardType:
                    const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(
                  labelText: 'Selling price',
                  helperText: 'Used on the online store and POS',
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: saving ? null : () => Navigator.pop(ctx),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: saving
                  ? null
                  : () async {
                      final price = double.tryParse(ctrl.text.trim());
                      if (price == null || price <= 0) {
                        setLocal(() => error = 'Enter a price greater than 0');
                        return;
                      }
                      setLocal(() {
                        saving = true;
                        error = null;
                      });
                      try {
                        final listId =
                            await ref.read(defaultPriceListProvider.future);
                        await ref.read(apiClientProvider).dio.post(
                          '/${ApiConstants.pricing}/price-lists/$listId/items',
                          data: {
                            'variantId': v.id,
                            'price': price,
                            'minQty': 1,
                          },
                        );
                        ref.invalidate(variantPricesProvider);
                        if (ctx.mounted) Navigator.pop(ctx);
                      } catch (e) {
                        setLocal(() {
                          saving = false;
                          error = '$e';
                        });
                      }
                    },
              child: saving
                  ? const SizedBox(
                      height: 16,
                      width: 16,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white))
                  : const Text('Save'),
            ),
          ],
        ),
      ),
    );
    ctrl.dispose();
  }

  Future<void> _saveVariant() async {
    if (_skuCtrl.text.trim().isEmpty) {
      setState(() => _saveError = 'SKU is required');
      return;
    }
    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.product}/admin/products/${widget.product.id}/variants',
        data: {
          'sku': _skuCtrl.text.trim(),
          if (_barcodeCtrl.text.trim().isNotEmpty)
            'barcode': _barcodeCtrl.text.trim(),
          if (_unitCtrl.text.trim().isNotEmpty)
            'unit': _unitCtrl.text.trim(),
        },
      );
      ref.invalidate(productVariantsProvider(widget.product.id));
      _skuCtrl.clear();
      _barcodeCtrl.clear();
      _unitCtrl.clear();
      setState(() {
        _saving = false;
        _showAddForm = false;
      });
    } catch (e) {
      setState(() {
        _saving = false;
        _saveError = e.toString();
      });
    }
  }
}

// ── Per-store assortment dialog ─────────────────────────────────────────────────

class _AssortmentDialog extends ConsumerStatefulWidget {
  final ProductInfo product;
  const _AssortmentDialog({required this.product});

  @override
  ConsumerState<_AssortmentDialog> createState() => _AssortmentDialogState();
}

class _AssortmentDialogState extends ConsumerState<_AssortmentDialog> {
  final Set<String> _selected = {};
  bool _allStores = true;
  bool _loaded = false;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    ref.read(productStoresProvider(widget.product.id).future).then((ids) {
      if (!mounted) return;
      setState(() {
        _selected
          ..clear()
          ..addAll(ids);
        _allStores = ids.isEmpty;
        _loaded = true;
      });
    }).catchError((_) {
      if (mounted) setState(() => _loaded = true);
    });
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    final ids = _allStores ? <String>[] : _selected.toList();
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.product}/admin/products/${widget.product.id}/stores',
        data: {'storeIds': ids},
      );
      ref.invalidate(productStoresProvider(widget.product.id));
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_allStores
              ? '${widget.product.name} is sold at all stores.'
              : '${widget.product.name} updated for ${ids.length} store(s).'),
        ),
      );
    } catch (e) {
      setState(() {
        _saving = false;
        _error = 'Could not save: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: Text('Sold at — ${widget.product.name}'),
      content: SizedBox(
        width: 420,
        child: !_loaded
            ? const SizedBox(
                height: 120, child: Center(child: CircularProgressIndicator()))
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_error != null) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(_error!,
                          style: TextStyle(color: cs.onErrorContainer)),
                    ),
                    const SizedBox(height: 12),
                  ],
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: _allStores,
                    onChanged: (v) => setState(() => _allStores = v),
                    title: const Text('Sell at all stores'),
                    subtitle: Text(
                      _allStores
                          ? 'Visible in every store (including new ones).'
                          : 'Choose the specific stores that carry this product.',
                      style: TextStyle(color: cs.outline, fontSize: 12),
                    ),
                  ),
                  if (!_allStores) ...[
                    const Divider(),
                    Flexible(
                      child: storesAsync.when(
                        loading: () => const Padding(
                          padding: EdgeInsets.all(16),
                          child: Center(child: CircularProgressIndicator()),
                        ),
                        error: (e, _) => Text('Could not load stores: $e',
                            style: TextStyle(color: cs.error)),
                        data: (stores) => SingleChildScrollView(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              for (final s in stores)
                                CheckboxListTile(
                                  contentPadding: EdgeInsets.zero,
                                  dense: true,
                                  controlAffinity:
                                      ListTileControlAffinity.leading,
                                  value: _selected.contains(s.id),
                                  onChanged: (v) => setState(() {
                                    if (v == true) {
                                      _selected.add(s.id);
                                    } else {
                                      _selected.remove(s.id);
                                    }
                                  }),
                                  title: Text(s.name),
                                  subtitle: Text(s.code,
                                      style: TextStyle(
                                          fontSize: 11, color: cs.outline)),
                                ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: (_saving || !_loaded) ? null : _save,
          child: _saving
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white))
              : const Text('Save'),
        ),
      ],
    );
  }
}
