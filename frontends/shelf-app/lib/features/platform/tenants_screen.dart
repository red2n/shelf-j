import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../admin/providers/admin_providers.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'tenant_onboarding_notifier.dart';

class TenantsScreen extends ConsumerWidget {
  const TenantsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenantsAsync = ref.watch(allTenantsProvider);
    final cs = Theme.of(context).colorScheme;

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
                    child: Text('Tenants',
                        style: Theme.of(context).textTheme.headlineMedium),
                  ),
                  tenantsAsync.when(
                    loading: () => const SizedBox.shrink(),
                    error: (_, _) => const SizedBox.shrink(),
                    data: (list) => Chip(
                      label: Text('${list.length} tenants'),
                      backgroundColor: cs.secondaryContainer,
                    ),
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
                    onPressed: () => _showOnboardingDialog(context, ref),
                    icon: const Icon(Icons.add_business),
                    label: const Text('Onboard New Tenant'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => ref.invalidate(allTenantsProvider),
                    icon: const Icon(Icons.refresh),
                    label: const Text('Refresh'),
                  ),
                ],
              ),
            ],
          ),
        ),

        // List
        Expanded(
          child: tenantsAsync.when(
            loading: () => const LoadingView(label: 'Loading tenants…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load tenants.'),
              onRetry: () => ref.invalidate(allTenantsProvider),
            ),
            data: (tenants) {
              if (tenants.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.business_outlined,
                          size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 16),
                      Text('No tenants yet',
                          style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      Text(
                        'Use "Onboard New Tenant" to add the first business.',
                        style: Theme.of(context)
                            .textTheme
                            .bodyMedium
                            ?.copyWith(color: cs.outline),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 24),
                      OutlinedButton.icon(
                        onPressed: () => _showOnboardingDialog(context, ref),
                        icon: const Icon(Icons.add_business),
                        label: const Text('Onboard New Tenant'),
                      ),
                    ],
                  ),
                );
              }

              return LayoutBuilder(builder: (context, bc) {
                final wide = bc.maxWidth >= 700;
                if (wide) {
                  return _WideTable(
                    tenants: tenants,
                    onToggleStatus: (t) => _toggleStatus(context, ref, t),
                  );
                }
                return _NarrowList(
                  tenants: tenants,
                  onToggleStatus: (t) => _toggleStatus(context, ref, t),
                );
              });
            },
          ),
        ),
      ],
    );
  }

  Future<void> _toggleStatus(
      BuildContext context, WidgetRef ref, PlatformTenant tenant) async {
    final activate = tenant.status.toUpperCase() != 'ACTIVE';
    final newStatus = activate ? 'ACTIVE' : 'INACTIVE';
    final label = activate ? 'Activate' : 'Deactivate';

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('$label "${tenant.name}"?'),
        content: Text(activate
            ? 'The tenant will regain access to the platform.'
            : 'The tenant and all their users will lose access to the platform.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: activate
                ? null
                : FilledButton.styleFrom(
                    backgroundColor: Theme.of(ctx).colorScheme.error,
                    foregroundColor: Theme.of(ctx).colorScheme.onError,
                  ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(label),
          ),
        ],
      ),
    );

    if (confirmed != true || !context.mounted) return;

    try {
      await ref.read(apiClientProvider).dio.patch(
        '/${ApiConstants.tenant}/platform/tenants/${tenant.id}/status',
        data: {'status': newStatus},
      );
      ref.invalidate(allTenantsProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(friendlyError(e,
                fallback: 'Failed to ${label.toLowerCase()} tenant.')),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    }
  }

  void _showOnboardingDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => _OnboardingDialog(onDone: () => ref.invalidate(allTenantsProvider)),
    );
  }
}

// ── Wide table ────────────────────────────────────────────────────────────────

class _WideTable extends StatelessWidget {
  final List<PlatformTenant> tenants;
  final void Function(PlatformTenant) onToggleStatus;
  const _WideTable({required this.tenants, required this.onToggleStatus});

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
                  DataColumn(label: Text('Business')),
                  DataColumn(label: Text('Country')),
                  DataColumn(label: Text('Currency')),
                  DataColumn(label: Text('Status')),
                  DataColumn(label: Text('Created')),
                  DataColumn(label: Text('')),
                ],
                rows: tenants.map((t) {
                  final active = t.status.toUpperCase() == 'ACTIVE';
                  return DataRow(cells: [
                    DataCell(Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(t.name,
                            style:
                                const TextStyle(fontWeight: FontWeight.bold)),
                        if (t.legalName != null)
                          Text(t.legalName!,
                              style:
                                  TextStyle(fontSize: 11, color: cs.outline)),
                      ],
                    )),
                    DataCell(Text(t.country)),
                    DataCell(Text(t.currency)),
                    DataCell(_StatusChip(active: active, label: t.status)),
                    DataCell(Text(
                      t.createdAt.length >= 10
                          ? t.createdAt.substring(0, 10)
                          : t.createdAt,
                      style: const TextStyle(
                          fontFamily: 'monospace', fontSize: 12),
                    )),
                    DataCell(
                      PopupMenuButton<String>(
                        icon: const Icon(Icons.more_vert),
                        tooltip: 'Actions',
                        itemBuilder: (_) => [
                          PopupMenuItem(
                            value: 'toggle',
                            child: Row(
                              children: [
                                Icon(
                                  active
                                      ? Icons.block_outlined
                                      : Icons.check_circle_outline,
                                  size: 18,
                                  color: active
                                      ? cs.error
                                      : Colors.green.shade700,
                                ),
                                const SizedBox(width: 8),
                                Text(active ? 'Deactivate' : 'Activate'),
                              ],
                            ),
                          ),
                        ],
                        onSelected: (_) => onToggleStatus(t),
                      ),
                    ),
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
  final List<PlatformTenant> tenants;
  final void Function(PlatformTenant) onToggleStatus;
  const _NarrowList({required this.tenants, required this.onToggleStatus});

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: tenants.length,
      separatorBuilder: (_, _) => const SizedBox(height: 4),
      itemBuilder: (context, i) {
        final t = tenants[i];
        final cs = Theme.of(context).colorScheme;
        final active = t.status.toUpperCase() == 'ACTIVE';
        return Card(
          child: ListTile(
            leading: CircleAvatar(
              backgroundColor: cs.primaryContainer,
              child: Text(
                t.name.isNotEmpty ? t.name[0].toUpperCase() : '?',
                style: TextStyle(
                    fontWeight: FontWeight.bold, color: cs.onPrimaryContainer),
              ),
            ),
            title: Text(t.name,
                style: const TextStyle(fontWeight: FontWeight.bold)),
            subtitle: Text('${t.country} · ${t.currency}'),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _StatusChip(active: active, label: t.status),
                PopupMenuButton<String>(
                  icon: const Icon(Icons.more_vert),
                  tooltip: 'Actions',
                  itemBuilder: (_) => [
                    PopupMenuItem(
                      value: 'toggle',
                      child: Row(
                        children: [
                          Icon(
                            active
                                ? Icons.block_outlined
                                : Icons.check_circle_outline,
                            size: 18,
                            color: active
                                ? cs.error
                                : Colors.green.shade700,
                          ),
                          const SizedBox(width: 8),
                          Text(active ? 'Deactivate' : 'Activate'),
                        ],
                      ),
                    ),
                  ],
                  onSelected: (_) => onToggleStatus(t),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

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
      child: Text(
        label,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.bold,
          color: active ? Colors.green.shade800 : cs.onErrorContainer,
        ),
      ),
    );
  }
}

// ── 2-step onboarding dialog ──────────────────────────────────────────────────

class _OnboardingDialog extends ConsumerStatefulWidget {
  final VoidCallback onDone;
  const _OnboardingDialog({required this.onDone});

  @override
  ConsumerState<_OnboardingDialog> createState() => _OnboardingDialogState();
}

class _OnboardingDialogState extends ConsumerState<_OnboardingDialog> {
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  bool _obscure = true;
  final _step0Key = GlobalKey<FormState>();

  final _bizNameCtrl = TextEditingController();
  final _storeNameCtrl = TextEditingController();
  final _storeCodeCtrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  String _country = 'IN';
  String _currency = 'INR';
  String _storeType = 'STORE';
  String _timezone = 'Asia/Kolkata';
  final _step1Key = GlobalKey<FormState>();

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    _bizNameCtrl.dispose();
    _storeNameCtrl.dispose();
    _storeCodeCtrl.dispose();
    _cityCtrl.dispose();
    super.dispose();
  }

  void _close() {
    ref.read(tenantOnboardingProvider.notifier).reset();
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final ob = ref.watch(tenantOnboardingProvider);
    final cs = Theme.of(context).colorScheme;

    // Refresh list when done
    ref.listen<TenantOnboardingState>(tenantOnboardingProvider, (_, next) {
      if (next.isDone) widget.onDone();
    });

    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: ob.isDone
              ? _DoneView(
                  tenantName: ob.tenantName ?? '',
                  ownerEmail: ob.ownerEmail ?? '',
                  onClose: _close,
                )
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.add_business, color: cs.primary),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            ob.step == 0
                                ? 'Step 1 of 2 — Owner account'
                                : 'Step 2 of 2 — Business & first store',
                            style: Theme.of(context)
                                .textTheme
                                .titleLarge
                                ?.copyWith(fontWeight: FontWeight.bold),
                          ),
                        ),
                        IconButton(
                          icon: const Icon(Icons.close),
                          tooltip: 'Close',
                          onPressed: ob.loading ? null : _close,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    LinearProgressIndicator(
                      value: ob.step == 0 ? 0.5 : 1.0,
                      borderRadius: BorderRadius.circular(4),
                    ),
                    const SizedBox(height: 16),
                    if (ob.error != null) ...[
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: cs.errorContainer,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Text(ob.error!,
                            style: TextStyle(color: cs.onErrorContainer)),
                      ),
                      const SizedBox(height: 12),
                    ],
                    if (ob.step == 0)
                      _AccountForm(
                        formKey: _step0Key,
                        emailCtrl: _emailCtrl,
                        passwordCtrl: _passwordCtrl,
                        obscure: _obscure,
                        onToggleObscure: () =>
                            setState(() => _obscure = !_obscure),
                        loading: ob.loading,
                        onNext: () {
                          if (!_step0Key.currentState!.validate()) return;
                          ref
                              .read(tenantOnboardingProvider.notifier)
                              .registerOwner(
                                email: _emailCtrl.text.trim(),
                                password: _passwordCtrl.text,
                              );
                        },
                      )
                    else
                      _BusinessStoreForm(
                        formKey: _step1Key,
                        bizNameCtrl: _bizNameCtrl,
                        storeNameCtrl: _storeNameCtrl,
                        storeCodeCtrl: _storeCodeCtrl,
                        cityCtrl: _cityCtrl,
                        country: _country,
                        currency: _currency,
                        storeType: _storeType,
                        timezone: _timezone,
                        onCountryChanged: (v) =>
                            setState(() => _country = v!),
                        onCurrencyChanged: (v) =>
                            setState(() => _currency = v!),
                        onTypeChanged: (v) =>
                            setState(() => _storeType = v!),
                        onTimezoneChanged: (v) =>
                            setState(() => _timezone = v!),
                        loading: ob.loading,
                        onSubmit: () {
                          if (!_step1Key.currentState!.validate()) return;
                          ref
                              .read(tenantOnboardingProvider.notifier)
                              .onboard(
                                businessName: _bizNameCtrl.text.trim(),
                                country: _country,
                                currency: _currency,
                                storeName: _storeNameCtrl.text.trim(),
                                storeCode: _storeCodeCtrl.text
                                    .trim()
                                    .toUpperCase(),
                                storeType: _storeType,
                                storeCity: _cityCtrl.text.trim(),
                                storeCountry: _country,
                                storeTimezone: _timezone,
                              );
                        },
                      ),
                  ],
                ),
        ),
      ),
    );
  }
}

// ── Form widgets (reused from previous version) ───────────────────────────────

class _AccountForm extends StatelessWidget {
  final GlobalKey<FormState> formKey;
  final TextEditingController emailCtrl;
  final TextEditingController passwordCtrl;
  final bool obscure;
  final VoidCallback onToggleObscure;
  final bool loading;
  final VoidCallback onNext;

  const _AccountForm({
    required this.formKey,
    required this.emailCtrl,
    required this.passwordCtrl,
    required this.obscure,
    required this.onToggleObscure,
    required this.loading,
    required this.onNext,
  });

  @override
  Widget build(BuildContext context) {
    return Form(
      key: formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Create the owner login credentials.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Theme.of(context).colorScheme.outline)),
          const SizedBox(height: 16),
          TextFormField(
            controller: emailCtrl,
            keyboardType: TextInputType.emailAddress,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              labelText: 'Owner email *',
              prefixIcon: Icon(Icons.email_outlined),
            ),
            validator: (v) =>
                v == null || !v.contains('@') ? 'Enter a valid email' : null,
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: passwordCtrl,
            obscureText: obscure,
            textInputAction: TextInputAction.done,
            decoration: InputDecoration(
              labelText: 'Password *',
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                icon:
                    Icon(obscure ? Icons.visibility_off : Icons.visibility),
                tooltip: obscure ? 'Show password' : 'Hide password',
                onPressed: onToggleObscure,
              ),
            ),
            validator: (v) =>
                v == null || v.length < 8 ? 'Min 8 characters' : null,
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: loading ? null : onNext,
            child: loading
                ?  SizedBox(
                    height: 18,
                    width: 18,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                : const Text('Next  →'),
          ),
        ],
      ),
    );
  }
}

class _BusinessStoreForm extends StatelessWidget {
  final GlobalKey<FormState> formKey;
  final TextEditingController bizNameCtrl;
  final TextEditingController storeNameCtrl;
  final TextEditingController storeCodeCtrl;
  final TextEditingController cityCtrl;
  final String country;
  final String currency;
  final String storeType;
  final String timezone;
  final ValueChanged<String?> onCountryChanged;
  final ValueChanged<String?> onCurrencyChanged;
  final ValueChanged<String?> onTypeChanged;
  final ValueChanged<String?> onTimezoneChanged;
  final bool loading;
  final VoidCallback onSubmit;

  const _BusinessStoreForm({
    required this.formKey,
    required this.bizNameCtrl,
    required this.storeNameCtrl,
    required this.storeCodeCtrl,
    required this.cityCtrl,
    required this.country,
    required this.currency,
    required this.storeType,
    required this.timezone,
    required this.onCountryChanged,
    required this.onCurrencyChanged,
    required this.onTypeChanged,
    required this.onTimezoneChanged,
    required this.loading,
    required this.onSubmit,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Form(
      key: formKey,
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const _SectionLabel(label: 'Business', icon: Icons.business_outlined),
            const SizedBox(height: 10),
            TextFormField(
              controller: bizNameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Business name *',
                hintText: 'e.g. Green Valley Supermarket',
                prefixIcon: Icon(Icons.business),
              ),
              validator: (v) =>
                  v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: country,
                    decoration: const InputDecoration(labelText: 'Country *'),
                    items: const [
                      DropdownMenuItem(value: 'IN', child: Text('India')),
                      DropdownMenuItem(value: 'US', child: Text('USA')),
                      DropdownMenuItem(value: 'GB', child: Text('UK')),
                      DropdownMenuItem(value: 'SG', child: Text('Singapore')),
                      DropdownMenuItem(value: 'AE', child: Text('UAE')),
                    ],
                    onChanged: onCountryChanged,
                    validator: (v) => v == null ? 'Required' : null,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: currency,
                    decoration:
                        const InputDecoration(labelText: 'Currency *'),
                    items: const [
                      DropdownMenuItem(value: 'INR', child: Text('INR')),
                      DropdownMenuItem(value: 'USD', child: Text('USD')),
                      DropdownMenuItem(value: 'GBP', child: Text('GBP')),
                      DropdownMenuItem(value: 'SGD', child: Text('SGD')),
                      DropdownMenuItem(value: 'AED', child: Text('AED')),
                    ],
                    onChanged: onCurrencyChanged,
                    validator: (v) => v == null ? 'Required' : null,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            Divider(color: cs.outlineVariant),
            const SizedBox(height: 8),
            const _SectionLabel(label: 'First Store', icon: Icons.store_outlined),
            const SizedBox(height: 10),
            TextFormField(
              controller: storeNameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Store name *',
                hintText: 'e.g. Main Street Branch',
                prefixIcon: Icon(Icons.store),
              ),
              validator: (v) =>
                  v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextFormField(
                    controller: storeCodeCtrl,
                    textCapitalization: TextCapitalization.characters,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(
                      labelText: 'Store code *',
                      hintText: 'STR-001',
                      prefixIcon: Icon(Icons.tag),
                    ),
                    validator: (v) =>
                        v == null || v.trim().isEmpty ? 'Required' : null,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: storeType,
                    decoration: const InputDecoration(labelText: 'Type'),
                    items: const [
                      DropdownMenuItem(
                          value: 'STORE', child: Text('Retail Store')),
                      DropdownMenuItem(
                          value: 'WAREHOUSE', child: Text('Warehouse')),
                    ],
                    onChanged: onTypeChanged,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextFormField(
                    controller: cityCtrl,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(labelText: 'City'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: timezone,
                    decoration:
                        const InputDecoration(labelText: 'Timezone'),
                    items: const [
                      DropdownMenuItem(
                          value: 'Asia/Kolkata', child: Text('IST')),
                      DropdownMenuItem(
                          value: 'America/New_York', child: Text('ET')),
                      DropdownMenuItem(
                          value: 'Europe/London', child: Text('GMT')),
                      DropdownMenuItem(
                          value: 'Asia/Singapore', child: Text('SGT')),
                      DropdownMenuItem(
                          value: 'Asia/Dubai', child: Text('GST')),
                    ],
                    onChanged: onTimezoneChanged,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: loading ? null : onSubmit,
              child: loading
                  ?  SizedBox(
                      height: 18,
                      width: 18,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                  : const Text('Create tenant & store'),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String label;
  final IconData icon;
  const _SectionLabel({required this.label, required this.icon});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      children: [
        Icon(icon, size: 16, color: cs.primary),
        const SizedBox(width: 6),
        Text(label,
            style: TextStyle(
                fontWeight: FontWeight.bold,
                color: cs.primary,
                fontSize: 13)),
      ],
    );
  }
}

class _DoneView extends StatelessWidget {
  final String tenantName;
  final String ownerEmail;
  final VoidCallback onClose;

  const _DoneView({
    required this.tenantName,
    required this.ownerEmail,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const SizedBox(height: 8),
        Container(
          width: 64,
          height: 64,
          decoration: BoxDecoration(
              color: cs.primaryContainer, shape: BoxShape.circle),
          child: Icon(Icons.check_circle_outline,
              size: 36, color: cs.onPrimaryContainer),
        ),
        const SizedBox(height: 16),
        Text('"$tenantName" is live!',
            style: Theme.of(context)
                .textTheme
                .titleLarge
                ?.copyWith(fontWeight: FontWeight.bold),
            textAlign: TextAlign.center),
        const SizedBox(height: 8),
        Text('Tenant and first store created. The list has been refreshed.',
            style: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(color: cs.outline),
            textAlign: TextAlign.center),
        const SizedBox(height: 20),
        Card(
          color: cs.surfaceContainerHigh,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Share with the owner:',
                    style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Icon(Icons.email_outlined, size: 16, color: cs.outline),
                    const SizedBox(width: 6),
                    Text(ownerEmail,
                        style: const TextStyle(fontFamily: 'monospace')),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  'They can log in at the app login screen with these credentials.',
                  style: TextStyle(fontSize: 12, color: cs.outline),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        FilledButton(onPressed: onClose, child: const Text('Done')),
      ],
    );
  }
}
