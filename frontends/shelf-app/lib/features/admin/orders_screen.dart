import 'package:flutter/material.dart';

class AdminOrdersScreen extends StatefulWidget {
  const AdminOrdersScreen({super.key});

  @override
  State<AdminOrdersScreen> createState() => _AdminOrdersScreenState();
}

class _AdminOrdersScreenState extends State<AdminOrdersScreen> {
  String _channel = 'ALL';

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Wrap(
            spacing: 16,
            runSpacing: 12,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text('Orders', style: Theme.of(context).textTheme.headlineMedium),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'ALL', label: Text('All')),
                  ButtonSegment(value: 'POS', label: Text('POS')),
                  ButtonSegment(value: 'ONLINE', label: Text('Online')),
                ],
                selected: {_channel},
                onSelectionChanged: (s) => setState(() => _channel = s.first),
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),
        const Expanded(
          child: Center(child: Text('Wires to order-svc — coming soon.')),
        ),
      ],
    );
  }
}
