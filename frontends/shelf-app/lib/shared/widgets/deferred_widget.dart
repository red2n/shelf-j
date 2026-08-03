import 'package:flutter/material.dart';

/// Wraps a screen whose code lives behind a Dart `deferred as` import, so a
/// shell (storefront/admin/POS/platform) only downloads another shell's code
/// when a route inside it is actually visited.
///
/// Shows [placeholder] while [libraryLoader] is in flight, then builds
/// [builder]. Dart memoizes `loadLibrary()`, so once a shell's library has
/// loaded, later navigation within that shell resolves immediately with no
/// re-fetch and no visible loading flash.
class DeferredWidget extends StatefulWidget {
  const DeferredWidget({
    super.key,
    required this.libraryLoader,
    required this.builder,
    this.placeholder,
    this.errorBuilder,
  });

  /// The compiler-generated `<lib>.loadLibrary` tear-off.
  final Future<void> Function() libraryLoader;

  /// Builds the real widget once the library has loaded.
  final WidgetBuilder builder;

  /// Shown while loading. Defaults to a centered [CircularProgressIndicator].
  final WidgetBuilder? placeholder;

  /// Shown if [libraryLoader] throws (e.g. offline mid-navigation). Receives
  /// the error and a retry callback. Defaults to a message + Retry button.
  final Widget Function(BuildContext context, Object error, VoidCallback retry)?
      errorBuilder;

  @override
  State<DeferredWidget> createState() => _DeferredWidgetState();
}

class _DeferredWidgetState extends State<DeferredWidget> {
  late Future<void> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.libraryLoader();
  }

  void _retry() {
    setState(() => _future = widget.libraryLoader());
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<void>(
      future: _future,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return widget.placeholder?.call(context) ?? const _DeferredPlaceholder();
        }
        if (snapshot.hasError) {
          return widget.errorBuilder?.call(context, snapshot.error!, _retry) ??
              _DeferredError(error: snapshot.error!, onRetry: _retry);
        }
        return widget.builder(context);
      },
    );
  }
}

class _DeferredPlaceholder extends StatelessWidget {
  const _DeferredPlaceholder();

  @override
  Widget build(BuildContext context) =>
      const Center(child: CircularProgressIndicator());
}

class _DeferredError extends StatelessWidget {
  const _DeferredError({required this.error, required this.onRetry});

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 32),
            const SizedBox(height: 8),
            const Text('Failed to load this section.'),
            const SizedBox(height: 8),
            FilledButton(onPressed: onRetry, child: const Text('Retry')),
          ],
        ),
      );
}
