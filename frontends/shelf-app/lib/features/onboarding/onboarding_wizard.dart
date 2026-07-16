import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'onboarding_notifier.dart';

class OnboardingWizard extends ConsumerStatefulWidget {
  const OnboardingWizard({super.key});

  @override
  ConsumerState<OnboardingWizard> createState() => _OnboardingWizardState();
}

class _OnboardingWizardState extends ConsumerState<OnboardingWizard> {
  final _pageCtrl = PageController();

  // Step 1 fields
  final _bizNameCtrl = TextEditingController();
  final _legalNameCtrl = TextEditingController();
  String _country = 'GB';
  String _currency = 'GBP';
  final _step1Key = GlobalKey<FormState>();

  static const _countryCurrency = {
    'GB': 'GBP',
    'US': 'USD',
    'IN': 'INR',
    'SG': 'SGD',
    'AE': 'AED',
  };

  // Step 2 fields
  final _storeNameCtrl = TextEditingController();
  final _storeCodeCtrl = TextEditingController();
  final _line1Ctrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  final _pincodeCtrl = TextEditingController();
  String _storeCountry = 'GB';
  String _timezone = 'Europe/London';
  String _storeType = 'STORE';
  final _step2Key = GlobalKey<FormState>();

  @override
  void dispose() {
    _pageCtrl.dispose();
    _bizNameCtrl.dispose();
    _legalNameCtrl.dispose();
    _storeNameCtrl.dispose();
    _storeCodeCtrl.dispose();
    _line1Ctrl.dispose();
    _cityCtrl.dispose();
    _pincodeCtrl.dispose();
    super.dispose();
  }

  void _animateTo(int page) =>
      _pageCtrl.animateToPage(page, duration: const Duration(milliseconds: 300), curve: Curves.easeInOut);

  @override
  Widget build(BuildContext context) {
    final ob = ref.watch(onboardingNotifierProvider);

    // When step 2 is done, router redirect will pick it up via auth state change
    ref.listen<OnboardingState>(onboardingNotifierProvider, (_, next) {
      if (next.step == 1 && !next.loading) _animateTo(1);
      if (next.step == 2 && !next.loading) context.go('/admin/dashboard');
    });

    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Column(
              children: [
                const SizedBox(height: 24),
                Icon(Icons.storefront_rounded, size: 40, color: cs.primary),
                const SizedBox(height: 4),
                Text('Set up your business',
                    style: Theme.of(context)
                        .textTheme
                        .headlineSmall
                        ?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: 4),
                // step indicators
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _StepDot(active: ob.step == 0, done: ob.step > 0, label: '1'),
                    const _StepLine(),
                    _StepDot(active: ob.step == 1, done: ob.step > 1, label: '2'),
                  ],
                ),
                const SizedBox(height: 16),
                if (ob.error != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(ob.error!, style: TextStyle(color: cs.onErrorContainer)),
                    ),
                  ),
                const SizedBox(height: 8),
                Expanded(
                  child: PageView(
                    controller: _pageCtrl,
                    physics: const NeverScrollableScrollPhysics(),
                    children: [
                      _Step1TenantForm(
                        formKey: _step1Key,
                        bizNameCtrl: _bizNameCtrl,
                        legalNameCtrl: _legalNameCtrl,
                        country: _country,
                        currency: _currency,
                        onCountryChanged: (v) => setState(() {
                          _country = v!;
                          _currency = _countryCurrency[v] ?? _currency;
                        }),
                        onCurrencyChanged: (v) => setState(() => _currency = v!),
                        loading: ob.loading,
                        onNext: () {
                          if (!_step1Key.currentState!.validate()) return;
                          ref.read(onboardingNotifierProvider.notifier).createTenant(
                                businessName: _bizNameCtrl.text.trim(),
                                legalName: _legalNameCtrl.text.trim(),
                                country: _country,
                                currency: _currency,
                              );
                        },
                      ),
                      _Step2StoreForm(
                        formKey: _step2Key,
                        nameCtrl: _storeNameCtrl,
                        codeCtrl: _storeCodeCtrl,
                        line1Ctrl: _line1Ctrl,
                        cityCtrl: _cityCtrl,
                        pincodeCtrl: _pincodeCtrl,
                        country: _storeCountry,
                        timezone: _timezone,
                        storeType: _storeType,
                        onCountryChanged: (v) => setState(() => _storeCountry = v!),
                        onTimezoneChanged: (v) => setState(() => _timezone = v!),
                        onTypeChanged: (v) => setState(() => _storeType = v!),
                        loading: ob.loading,
                        onSubmit: () {
                          if (!_step2Key.currentState!.validate()) return;
                          ref.read(onboardingNotifierProvider.notifier).createStore(
                                name: _storeNameCtrl.text.trim(),
                                code: _storeCodeCtrl.text.trim().toUpperCase(),
                                type: _storeType,
                                line1: _line1Ctrl.text.trim(),
                                city: _cityCtrl.text.trim(),
                                country: _storeCountry,
                                pincode: _pincodeCtrl.text.trim(),
                                timezone: _timezone,
                              );
                        },
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ── Step 1: Tenant info ────────────────────────────────────────────────────────

class _Step1TenantForm extends StatelessWidget {
  final GlobalKey<FormState> formKey;
  final TextEditingController bizNameCtrl;
  final TextEditingController legalNameCtrl;
  final String country;
  final String currency;
  final ValueChanged<String?> onCountryChanged;
  final ValueChanged<String?> onCurrencyChanged;
  final bool loading;
  final VoidCallback onNext;

  const _Step1TenantForm({
    required this.formKey,
    required this.bizNameCtrl,
    required this.legalNameCtrl,
    required this.country,
    required this.currency,
    required this.onCountryChanged,
    required this.onCurrencyChanged,
    required this.loading,
    required this.onNext,
  });

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Form(
        key: formKey,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Business details',
                style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            Text('Tell us about your business.',
                style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: 24),
            TextFormField(
              controller: bizNameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Business name *',
                hintText: 'e.g. Green Valley Supermarket',
                prefixIcon: Icon(Icons.business),
              ),
              validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: legalNameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Legal / registered name (optional)',
                prefixIcon: Icon(Icons.balance),
              ),
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: country,
              decoration: const InputDecoration(
                labelText: 'Country *',
                prefixIcon: Icon(Icons.flag_outlined),
              ),
              items: const [
                DropdownMenuItem(value: 'IN', child: Text('India (IN)')),
                DropdownMenuItem(value: 'US', child: Text('United States (US)')),
                DropdownMenuItem(value: 'GB', child: Text('United Kingdom (GB)')),
                DropdownMenuItem(value: 'SG', child: Text('Singapore (SG)')),
                DropdownMenuItem(value: 'AE', child: Text('UAE (AE)')),
              ],
              onChanged: onCountryChanged,
              validator: (v) => v == null ? 'Required' : null,
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: currency,
              decoration: const InputDecoration(
                labelText: 'Currency *',
                prefixIcon: Icon(Icons.currency_exchange),
              ),
              items: const [
                DropdownMenuItem(value: 'INR', child: Text('INR — Indian Rupee')),
                DropdownMenuItem(value: 'USD', child: Text('USD — US Dollar')),
                DropdownMenuItem(value: 'GBP', child: Text('GBP — British Pound')),
                DropdownMenuItem(value: 'SGD', child: Text('SGD — Singapore Dollar')),
                DropdownMenuItem(value: 'AED', child: Text('AED — UAE Dirham')),
              ],
              onChanged: onCurrencyChanged,
              validator: (v) => v == null ? 'Required' : null,
            ),
            const SizedBox(height: 32),
            FilledButton(
              onPressed: loading ? null : onNext,
              child: loading
                  ? const SizedBox(
                      height: 20, width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : const Text('Continue  →'),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Step 2: First store ────────────────────────────────────────────────────────

class _Step2StoreForm extends StatelessWidget {
  final GlobalKey<FormState> formKey;
  final TextEditingController nameCtrl;
  final TextEditingController codeCtrl;
  final TextEditingController line1Ctrl;
  final TextEditingController cityCtrl;
  final TextEditingController pincodeCtrl;
  final String country;
  final String timezone;
  final String storeType;
  final ValueChanged<String?> onCountryChanged;
  final ValueChanged<String?> onTimezoneChanged;
  final ValueChanged<String?> onTypeChanged;
  final bool loading;
  final VoidCallback onSubmit;

  const _Step2StoreForm({
    required this.formKey,
    required this.nameCtrl,
    required this.codeCtrl,
    required this.line1Ctrl,
    required this.cityCtrl,
    required this.pincodeCtrl,
    required this.country,
    required this.timezone,
    required this.storeType,
    required this.onCountryChanged,
    required this.onTimezoneChanged,
    required this.onTypeChanged,
    required this.loading,
    required this.onSubmit,
  });

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Form(
        key: formKey,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Your first store',
                style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            Text('You can add more stores later from the admin panel.',
                style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: 24),
            TextFormField(
              controller: nameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Store name *',
                hintText: 'e.g. Main Street Branch',
                prefixIcon: Icon(Icons.store),
              ),
              validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: codeCtrl,
              textInputAction: TextInputAction.next,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(
                labelText: 'Store code * (e.g. STR-001)',
                hintText: 'Short unique code',
                prefixIcon: Icon(Icons.tag),
              ),
              validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: storeType,
              decoration: const InputDecoration(
                labelText: 'Type',
                prefixIcon: Icon(Icons.category_outlined),
              ),
              items: const [
                DropdownMenuItem(value: 'STORE', child: Text('Retail Store')),
                DropdownMenuItem(value: 'WAREHOUSE', child: Text('Warehouse')),
              ],
              onChanged: onTypeChanged,
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: line1Ctrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Address line 1',
                prefixIcon: Icon(Icons.location_on_outlined),
              ),
            ),
            const SizedBox(height: 16),
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
                  child: TextFormField(
                    controller: pincodeCtrl,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(labelText: 'Pincode / ZIP'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: country,
              decoration: const InputDecoration(
                labelText: 'Country',
                prefixIcon: Icon(Icons.flag_outlined),
              ),
              items: const [
                DropdownMenuItem(value: 'IN', child: Text('India')),
                DropdownMenuItem(value: 'US', child: Text('United States')),
                DropdownMenuItem(value: 'GB', child: Text('United Kingdom')),
                DropdownMenuItem(value: 'SG', child: Text('Singapore')),
                DropdownMenuItem(value: 'AE', child: Text('UAE')),
              ],
              onChanged: onCountryChanged,
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: timezone,
              decoration: const InputDecoration(
                labelText: 'Timezone',
                prefixIcon: Icon(Icons.schedule),
              ),
              items: const [
                DropdownMenuItem(value: 'Asia/Kolkata', child: Text('Asia/Kolkata (IST)')),
                DropdownMenuItem(value: 'America/New_York', child: Text('America/New_York (ET)')),
                DropdownMenuItem(value: 'America/Los_Angeles', child: Text('America/Los_Angeles (PT)')),
                DropdownMenuItem(value: 'Europe/London', child: Text('Europe/London (GMT)')),
                DropdownMenuItem(value: 'Asia/Singapore', child: Text('Asia/Singapore (SGT)')),
                DropdownMenuItem(value: 'Asia/Dubai', child: Text('Asia/Dubai (GST)')),
              ],
              onChanged: onTimezoneChanged,
            ),
            const SizedBox(height: 32),
            FilledButton(
              onPressed: loading ? null : onSubmit,
              child: loading
                  ? const SizedBox(
                      height: 20, width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : const Text('Create store & finish setup'),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

class _StepDot extends StatelessWidget {
  final bool active;
  final bool done;
  final String label;

  const _StepDot({required this.active, required this.done, required this.label});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final bg = done ? cs.primary : active ? cs.primaryContainer : cs.surfaceContainerHighest;
    final fg = done ? cs.onPrimary : active ? cs.onPrimaryContainer : cs.onSurfaceVariant;
    return Container(
      width: 32,
      height: 32,
      decoration: BoxDecoration(color: bg, shape: BoxShape.circle),
      child: Center(
        child: done
            ? Icon(Icons.check, size: 18, color: fg)
            : Text(label, style: TextStyle(color: fg, fontWeight: FontWeight.bold)),
      ),
    );
  }
}

class _StepLine extends StatelessWidget {
  const _StepLine();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 48,
      height: 2,
      color: Theme.of(context).colorScheme.outlineVariant,
    );
  }
}
