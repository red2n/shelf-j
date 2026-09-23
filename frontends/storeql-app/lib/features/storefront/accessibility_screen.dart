import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'storefront_providers.dart';
import 'survey_widgets.dart' show showFeedbackSheet;
import '../../core/spacing.dart';

/// The shop's accessibility statement (12.11): what the European Accessibility Act, Directive (EU)
/// 2019/882 art.13 and Annex V, asks a service provider to publish, and what the UK's Equality Act
/// 2010 leads a shopper to expect — how the shop meets WCAG 2.1 AA, what it knows falls short, how
/// to report a barrier, and where to turn if the answer is not good enough. Public: no sign-in.
class StorefrontAccessibilityScreen extends ConsumerWidget {
  const StorefrontAccessibilityScreen({super.key});

  /// The day this statement was last checked against the app. Moved with every revision of it.
  static const reviewed = '15 September 2026';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storeName = ref.watch(storefrontConfigProvider).value?.storeName;
    final text = Theme.of(context).textTheme;
    // The config falls back to a '-' name when it cannot be read; never open the statement with it.
    final shop =
        (storeName == null || storeName.trim().isEmpty || storeName == '-')
            ? 'This shop'
            : storeName;

    Widget heading(String s) => Padding(
          padding: const EdgeInsets.only(top: 24, bottom: 8),
          child:
              Semantics(header: true, child: Text(s, style: text.titleMedium)),
        );
    Widget para(String s) => Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text(s, style: text.bodyMedium),
        );
    Widget bullet(String s) => Padding(
          padding: const EdgeInsets.only(bottom: 6),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const ExcludeSemantics(child: Text('•  ')),
              Expanded(child: Text(s, style: text.bodyMedium)),
            ],
          ),
        );

    return ContentBounds(
      child: ListView(
        padding: context.pagePadding,
        children: [
          Semantics(
            header: true,
            child: Text('Accessibility statement', style: text.headlineSmall),
          ),
          const SizedBox(height: 12),
          para(
              '$shop wants everyone to be able to browse, buy and track an order here, '
              'including people who use a screen reader, a keyboard, magnification or '
              'larger text.'),
          heading('How far this shop meets the standard'),
          para(
              'This online shop is partially conformant with the Web Content Accessibility '
              'Guidelines (WCAG) 2.1 at level AA, the standard EN 301 549 v3.2.1 cites for the '
              'European Accessibility Act. "Partially" because it has not yet been audited by an '
              'independent tester; the checks below are the ones it passes today.'),
          bullet(
              'Every button, link and field is named for a screen reader, and pictures that '
              'only repeat the product name beside them are hidden from it.'),
          bullet(
              'Everything can be reached and used with a keyboard, in reading order.'),
          bullet(
              'Text has a contrast of at least 4.5 to 1 against its background, and the '
              'edges of controls at least 3 to 1.'),
          bullet(
              'Text can be enlarged to twice its size without anything being cut off.'),
          bullet(
              'Screen readers work from the first page, with no extra step to switch them on.'),
          bullet('Every control is at least 24 by 24 pixels to press.'),
          bullet(
              'The moving offers can be paused, and stay still for anyone who has asked their '
              'device to reduce motion.'),
          heading('What is known not to work well yet'),
          bullet(
              'Product photos a shop uploads have no written description of their own; the '
              'product name and details are always given in text beside them.'),
          bullet(
              'The till and the back-office screens used by staff are not covered by this '
              'statement.'),
          heading('Tell us about a barrier'),
          para(
              'If something here stops you, or you need information in another format, tell '
              'us and we will reply.'),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.tonalIcon(
              key: const Key('accessibility-feedback'),
              icon: const Icon(Icons.feedback_outlined),
              label: const Text('Report an accessibility problem'),
              onPressed: () => showFeedbackSheet(context),
            ),
          ),
          heading('If you are not satisfied'),
          para(
              'In Great Britain, the Equality Advisory and Support Service (EASS) can advise you, '
              'and the Equality and Human Rights Commission enforces the Equality Act 2010. In '
              'Northern Ireland, contact the Equality Commission for Northern Ireland. In the '
              'European Union, each country names a market surveillance authority for the '
              'European Accessibility Act that you can complain to.'),
          heading('About this statement'),
          para(
              'Last reviewed $reviewed. It is reviewed whenever the shop changes, against '
              'automated accessibility checks run on every release and by hand with a keyboard '
              'and a screen reader.'),
        ],
      ),
    );
  }
}
