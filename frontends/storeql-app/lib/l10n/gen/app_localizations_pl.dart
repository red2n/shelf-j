// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Polish (`pl`).
class AppLocalizationsPl extends AppLocalizations {
  AppLocalizationsPl([String locale = 'pl']) : super(locale);

  @override
  String get signInToContinue => 'Zaloguj się, aby kontynuować';

  @override
  String get createYourAccount => 'Utwórz konto';

  @override
  String get fieldEmail => 'E-mail';

  @override
  String get fieldEmailInvalid => 'Wprowadź prawidłowy adres e-mail';

  @override
  String get fieldPhoneOptional => 'Telefon (opcjonalnie)';

  @override
  String get fieldPassword => 'Hasło';

  @override
  String get fieldPasswordTooShort => 'Minimum 8 znaków';

  @override
  String get actionSignIn => 'Zaloguj się';

  @override
  String get actionCreateAccount => 'Utwórz konto';

  @override
  String get toggleHaveAccount => 'Masz już konto? Zaloguj się';

  @override
  String get toggleNewHere => 'Nowy użytkownik? Utwórz konto';

  @override
  String get errInvalidCredentials => 'Nieprawidłowy e-mail lub hasło.';

  @override
  String get errEmailExists => 'Konto z tym adresem e-mail już istnieje.';

  @override
  String get errNetwork =>
      'Nie można połączyć się z serwerem. Sprawdź połączenie.';

  @override
  String get errGeneric => 'Coś poszło nie tak. Spróbuj ponownie.';
}
