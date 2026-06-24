// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Panjabi Punjabi (`pa`).
class AppLocalizationsPa extends AppLocalizations {
  AppLocalizationsPa([String locale = 'pa']) : super(locale);

  @override
  String get signInToContinue => 'Sign in to continue';

  @override
  String get createYourAccount => 'Create your account';

  @override
  String get fieldEmail => 'Email';

  @override
  String get fieldEmailInvalid => 'Enter a valid email';

  @override
  String get fieldPhoneOptional => 'Phone (optional)';

  @override
  String get fieldPassword => 'Password';

  @override
  String get fieldPasswordTooShort => 'Minimum 8 characters';

  @override
  String get actionSignIn => 'Sign in';

  @override
  String get actionCreateAccount => 'Create account';

  @override
  String get toggleHaveAccount => 'Already have an account? Sign in';

  @override
  String get toggleNewHere => 'New here? Create an account';

  @override
  String get errInvalidCredentials => 'Invalid email or password.';

  @override
  String get errEmailExists => 'An account with this email already exists.';

  @override
  String get errNetwork => 'Cannot reach the server. Check your connection.';

  @override
  String get errGeneric => 'Something went wrong. Please try again.';
}
