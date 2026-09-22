// The languages a message can be written in (13.x, message templates), by their ISO 639 codes:
// the app's own languages first, then a few more a business is likely to write in. Anything else
// is typed as its code.

/// The languages that can be picked without typing a code.
const Map<String, String> knownLanguages = {
  'en': 'English',
  'pl': 'Polish',
  'ro': 'Romanian',
  'pa': 'Punjabi',
  'ur': 'Urdu',
  'bn': 'Bengali',
  'gu': 'Gujarati',
  'ar': 'Arabic',
  'hi': 'Hindi',
  'cy': 'Welsh',
  'fr': 'French',
  'de': 'German',
  'es': 'Spanish',
  'pt': 'Portuguese',
  'it': 'Italian',
};

/// The language's English name, or its code when it has none here.
String languageName(String code) => knownLanguages[code] ?? code;
