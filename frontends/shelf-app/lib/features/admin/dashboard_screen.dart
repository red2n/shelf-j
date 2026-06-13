import 'package:flutter/material.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Dashboard', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 24),
          Wrap(
            spacing: 16,
            runSpacing: 16,
            children: const [
              _StatCard(label: "Today's Revenue", value: '—', icon: Icons.attach_money),
              _StatCard(label: 'Orders Today', value: '—', icon: Icons.receipt_long),
              _StatCard(label: 'Low Stock Items', value: '—', icon: Icons.warning_amber_outlined),
              _StatCard(label: 'Active Staff', value: '—', icon: Icons.people_outline),
            ],
          ),
          const SizedBox(height: 32),
          Text('Recent Orders', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 12),
          const Center(
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 32),
              child: Text('Wires to order-svc — coming soon.'),
            ),
          ),
        ],
      ),
    );
  }
}

class _StatCard extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;

  const _StatCard({
    required this.label,
    required this.value,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SizedBox(
      width: 180,
      child: Card(
        color: cs.primaryContainer,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: cs.onPrimaryContainer),
              const SizedBox(height: 12),
              Text(
                value,
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      color: cs.onPrimaryContainer,
                      fontWeight: FontWeight.bold,
                    ),
              ),
              const SizedBox(height: 4),
              Text(label, style: TextStyle(color: cs.onPrimaryContainer)),
            ],
          ),
        ),
      ),
    );
  }
}
