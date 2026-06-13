import 'package:flutter/material.dart';

class ReportsScreen extends StatelessWidget {
  const ReportsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Reports', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 24),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              _ReportTile(title: 'Z-Report (EOD)', icon: Icons.today, onTap: () {}),
              _ReportTile(title: 'Sales Summary', icon: Icons.trending_up, onTap: () {}),
              _ReportTile(title: 'Inventory Valuation', icon: Icons.inventory_2, onTap: () {}),
              _ReportTile(title: 'Staff Performance', icon: Icons.people, onTap: () {}),
            ],
          ),
          const SizedBox(height: 32),
          const Center(child: Text('Wires to reporting-svc — coming soon.')),
        ],
      ),
    );
  }
}

class _ReportTile extends StatelessWidget {
  final String title;
  final IconData icon;
  final VoidCallback onTap;

  const _ReportTile({required this.title, required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 28),
              const SizedBox(width: 12),
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(width: 12),
              const Icon(Icons.chevron_right),
            ],
          ),
        ),
      ),
    );
  }
}
