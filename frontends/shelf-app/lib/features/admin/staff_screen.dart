import 'package:flutter/material.dart';

class StaffScreen extends StatelessWidget {
  const StaffScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Row(
            children: [
              Text('Staff', style: Theme.of(context).textTheme.headlineMedium),
              const Spacer(),
              FilledButton.icon(
                onPressed: () {},
                icon: const Icon(Icons.person_add),
                label: const Text('Invite Staff'),
              ),
            ],
          ),
        ),
        const Expanded(
          child: Center(child: Text('Wires to iam-svc — coming soon.')),
        ),
      ],
    );
  }
}
