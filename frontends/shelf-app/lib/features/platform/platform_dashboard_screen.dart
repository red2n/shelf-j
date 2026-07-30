import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/theme.dart';

class PlatformDashboardScreen extends ConsumerWidget {
  const PlatformDashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final email = auth is AuthAuthenticated ? (auth.email ?? 'Platform Admin') : 'Platform Admin';
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header
          Row(
            children: [
              CircleAvatar(
                backgroundColor: cs.primaryContainer,
                radius: 24,
                child: Icon(Icons.admin_panel_settings, color: cs.onPrimaryContainer),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Platform Overview', style: tt.headlineMedium?.copyWith(fontWeight: FontWeight.bold)),
                    Text(email, style: tt.bodyMedium?.copyWith(color: cs.outline)),
                  ],
                ),
              ),
              Chip(
                avatar: Icon(Icons.verified_user, size: 16, color: cs.onPrimary),
                label: const Text('PLATFORM ADMIN'),
                backgroundColor: cs.primary,
                labelStyle: TextStyle(color: cs.onPrimary, fontWeight: FontWeight.bold, fontSize: 11),
              ),
            ],
          ),
          const SizedBox(height: 32),

          // Info cards grid
          LayoutBuilder(builder: (context, constraints) {
            final wide = constraints.maxWidth >= 700;
            return Wrap(
              spacing: 16,
              runSpacing: 16,
              children: [
                _InfoCard(
                  width: wide ? (constraints.maxWidth - 32) / 3 : constraints.maxWidth,
                  icon: Icons.business,
                  color: cs.primaryContainer,
                  iconColor: cs.onPrimaryContainer,
                  title: 'Tenant Management',
                  subtitle: 'Tenants self-register via the onboarding wizard. Use the Tenants tab to monitor them.',
                ),
                _InfoCard(
                  width: wide ? (constraints.maxWidth - 32) / 3 : constraints.maxWidth,
                  icon: Icons.lock_open_outlined,
                  color: cs.secondaryContainer,
                  iconColor: cs.onSecondaryContainer,
                  title: 'Bootstrap Complete',
                  subtitle: 'Your platform admin account is active. The /bootstrap/admin endpoint is now locked.',
                ),
                _InfoCard(
                  width: wide ? (constraints.maxWidth - 32) / 3 : constraints.maxWidth,
                  icon: Icons.health_and_safety_outlined,
                  color: cs.tertiaryContainer,
                  iconColor: cs.onTertiaryContainer,
                  title: 'All Services Healthy',
                  subtitle: 'Gateway, IAM, Tenant, Order, Inventory, Pricing, Payment — all running.',
                ),
              ],
            );
          }),
          const SizedBox(height: 32),

          // Getting started card
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.rocket_launch_outlined, color: cs.primary),
                      const SizedBox(width: 8),
                      Text('How Tenant Onboarding Works',
                          style: tt.titleMedium?.copyWith(
                            color: cs.primary,
                            fontWeight: FontWeight.bold,
                          )),
                    ],
                  ),
                  const SizedBox(height: 16),
                  const _Step(n: 1, text: 'A business owner goes to the app and clicks "New here? Create an account."'),
                  const _Step(n: 2, text: 'They register with their email and password — they get a CUSTOMER role initially.'),
                  const _Step(n: 3, text: 'The onboarding wizard immediately appears: they enter their business name, country, and currency.'),
                  const _Step(n: 4, text: 'Next they add their first store (name, address, timezone). The OWNER role is automatically granted.'),
                  const _Step(n: 5, text: 'They land on the Admin Dashboard and can manage inventory, orders, and staff from there.'),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),

          // API reference card
          Card(
            color: cs.surfaceContainerHighest,
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.api, color: cs.onSurfaceVariant),
                      const SizedBox(width: 8),
                      Text('Key Platform Endpoints',
                          style: tt.titleSmall?.copyWith(fontWeight: FontWeight.bold)),
                    ],
                  ),
                  const SizedBox(height: 12),
                  const _ApiRow(method: 'POST', path: '/api/iam-svc/auth/register', desc: 'Tenant owner self-registers'),
                  const _ApiRow(method: 'POST', path: '/api/tenant-svc/onboarding/tenants', desc: 'Create tenant (step 1)'),
                  const _ApiRow(method: 'POST', path: '/api/tenant-svc/onboarding/stores', desc: 'Create first store (step 2)'),
                  const _ApiRow(method: 'GET', path: '/api/tenant-svc/admin/stores', desc: 'List tenant\'s stores'),
                  const _ApiRow(method: 'GET', path: '/api/order-svc/orders', desc: 'List tenant orders'),
                  const _ApiRow(method: 'GET', path: '/api/inventory-svc/admin/inventory/levels', desc: 'Stock levels'),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _InfoCard extends StatelessWidget {
  final double width;
  final IconData icon;
  final Color color;
  final Color iconColor;
  final String title;
  final String subtitle;

  const _InfoCard({
    required this.width,
    required this.icon,
    required this.color,
    required this.iconColor,
    required this.title,
    required this.subtitle,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width,
      child: Card(
        color: color,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: iconColor, size: 32),
              const SizedBox(height: 12),
              Text(title,
                  style: Theme.of(context)
                      .textTheme
                      .titleSmall
                      ?.copyWith(fontWeight: FontWeight.bold)),
              const SizedBox(height: 6),
              Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      ),
    );
  }
}

class _Step extends StatelessWidget {
  final int n;
  final String text;

  const _Step({required this.n, required this.text});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 24,
            height: 24,
            decoration: BoxDecoration(color: cs.primary, shape: BoxShape.circle),
            child: Center(
              child: Text('$n',
                  style: TextStyle(
                      color: cs.onPrimary, fontSize: 12, fontWeight: FontWeight.bold)),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(child: Text(text, style: Theme.of(context).textTheme.bodyMedium)),
        ],
      ),
    );
  }
}

class _ApiRow extends StatelessWidget {
  final String method;
  final String path;
  final String desc;

  const _ApiRow({required this.method, required this.path, required this.desc});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isGet = method == 'GET';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Container(
            width: 44,
            padding: const EdgeInsets.symmetric(vertical: 2),
            decoration: BoxDecoration(
              color: (isGet ? context.status.success : context.status.info)
                  .withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              method,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.bold,
                color: isGet ? context.status.success : context.status.info,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(path,
                style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                    color: cs.onSurfaceVariant)),
          ),
          const SizedBox(width: 8),
          Flexible(
            child: Text(desc,
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: cs.outline)),
          ),
        ],
      ),
    );
  }
}
