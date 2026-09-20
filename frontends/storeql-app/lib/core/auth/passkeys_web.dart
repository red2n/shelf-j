import 'dart:convert';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:typed_data';

import 'package:web/web.dart' as web;

import 'passkeys.dart';

/// navigator.credentials, for the server's options and back to the server's fields.
class _WebPasskeys implements Passkeys {
  const _WebPasskeys();

  @override
  bool get supported => web.window.isSecureContext && (web.window as JSObject).has('PublicKeyCredential');

  @override
  Future<Map<String, String>> create(Map<String, dynamic> o) async {
    final publicKey = web.PublicKeyCredentialCreationOptions(
      rp: web.PublicKeyCredentialRpEntity(name: o['rpName'] as String, id: o['rpId'] as String),
      user: web.PublicKeyCredentialUserEntity(
        name: o['userName'] as String,
        id: _bytes(o['userId'] as String),
        displayName: o['userName'] as String,
      ),
      challenge: _bytes(o['challenge'] as String),
      pubKeyCredParams: [
        for (final alg in o['algorithms'] as List<dynamic>)
          web.PublicKeyCredentialParameters(type: 'public-key', alg: (alg as num).toInt()),
      ].toJS,
      timeout: (o['timeoutMillis'] as num).toInt(),
      attestation: o['attestation'] as String,
      excludeCredentials: [
        for (final id in o['excludeCredentials'] as List<dynamic>)
          web.PublicKeyCredentialDescriptor(type: 'public-key', id: _bytes(id as String)),
      ].toJS,
      authenticatorSelection: web.AuthenticatorSelectionCriteria(
        residentKey: 'preferred',
        userVerification: o['userVerification'] as String,
      ),
    );
    final made = await _ceremony(
      () => web.window.navigator.credentials.create(web.CredentialCreationOptions(publicKey: publicKey)).toDart,
    );
    final response = (made as web.PublicKeyCredential).response as web.AuthenticatorAttestationResponse;
    return {
      'clientDataJson': _text(response.clientDataJSON),
      'attestationObject': _text(response.attestationObject),
    };
  }

  @override
  Future<Map<String, String>> get(Map<String, dynamic> o) async {
    final publicKey = web.PublicKeyCredentialRequestOptions(
      challenge: _bytes(o['challenge'] as String),
      rpId: o['rpId'] as String,
      timeout: (o['timeoutMillis'] as num).toInt(),
      userVerification: o['userVerification'] as String,
      allowCredentials: [
        for (final id in o['allowCredentials'] as List<dynamic>)
          web.PublicKeyCredentialDescriptor(type: 'public-key', id: _bytes(id as String)),
      ].toJS,
    );
    final got = await _ceremony(
      () => web.window.navigator.credentials.get(web.CredentialRequestOptions(publicKey: publicKey)).toDart,
    );
    final credential = got as web.PublicKeyCredential;
    final response = credential.response as web.AuthenticatorAssertionResponse;
    return {
      'credentialId': _text(credential.rawId),
      'clientDataJson': _text(response.clientDataJSON),
      'authenticatorData': _text(response.authenticatorData),
      'signature': _text(response.signature),
    };
  }

  /// The browser refuses with a DOMException when the person closes the prompt,
  /// when it times out, or when no matching passkey is on this device.
  Future<web.Credential> _ceremony(Future<web.Credential?> Function() run) async {
    try {
      final credential = await run();
      if (credential == null) throw const PasskeyCancelled();
      return credential;
    } on PasskeyCancelled {
      rethrow;
    } catch (_) {
      throw const PasskeyCancelled();
    }
  }

  static JSArrayBuffer _bytes(String base64url) =>
      Uint8List.fromList(base64Url.decode(base64Url.normalize(base64url))).buffer.toJS;

  static String _text(JSArrayBuffer buffer) =>
      base64Url.encode(buffer.toDart.asUint8List()).replaceAll('=', '');
}

Passkeys platformPasskeys() => const _WebPasskeys();
