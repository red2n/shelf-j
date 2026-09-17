// A passkey authenticator in software, for the k6 suites (20.12): a P-256 key made with WebCrypto,
// a credential id, a signature counter, and the two WebAuthn ceremonies as a browser and an
// authenticator would produce them between them — so the platform's verifier is driven live with
// bytes it did not make itself. Attestation is "none", as the platform asks.
import encoding from 'k6/encoding';

const UP = 0x01; // user present
const UV = 0x04; // user verified
const AT = 0x40; // attested credential data follows

const b64url = (bytes) => encoding.b64encode(bytes instanceof Uint8Array ? bytes.buffer : bytes, 'rawurl');
const utf8 = (text) => new Uint8Array([...unescape(encodeURIComponent(text))].map((c) => c.charCodeAt(0)));

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

/** CBOR: a byte string, and a text string shorter than 24 bytes. */
function cborBytes(bytes) {
  const head = bytes.length < 24 ? [0x40 | bytes.length] : bytes.length < 256 ? [0x58, bytes.length] : [0x59, bytes.length >> 8, bytes.length & 0xff];
  return concat(new Uint8Array(head), bytes);
}
const cborText = (text) => concat(new Uint8Array([0x60 | text.length]), utf8(text));

/** An ECDSA signature as WebCrypto gives it (r || s) to the ASN.1 DER WebAuthn carries. */
function der(raw) {
  const int = (bytes) => {
    let i = 0;
    while (i < bytes.length - 1 && bytes[i] === 0) i++;
    let v = bytes.slice(i);
    if (v[0] & 0x80) v = concat(new Uint8Array([0]), v);
    return concat(new Uint8Array([0x02, v.length]), v);
  };
  const body = concat(int(raw.slice(0, 32)), int(raw.slice(32)));
  return concat(new Uint8Array([0x30, body.length]), body);
}

export class SoftwarePasskey {
  constructor(rpId, keys, publicRaw) {
    this.rpId = rpId;
    this.keys = keys;
    this.x = publicRaw.slice(1, 33);
    this.y = publicRaw.slice(33, 65);
    this.credentialId = crypto.getRandomValues(new Uint8Array(32));
    this.counter = 0;
  }

  static async create(rpId) {
    const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
    return new SoftwarePasskey(rpId, keys, new Uint8Array(await crypto.subtle.exportKey('raw', keys.publicKey)));
  }

  get credentialIdText() {
    return b64url(this.credentialId);
  }

  clientData(type, challenge, origin) {
    return utf8(JSON.stringify({ type, challenge, origin, crossOrigin: false }));
  }

  async authData(flags) {
    const rpHash = new Uint8Array(await crypto.subtle.digest('SHA-256', utf8(this.rpId).buffer));
    const n = this.counter;
    return concat(rpHash, new Uint8Array([flags, (n >> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff]));
  }

  /** kty EC2, alg ES256, crv P-256, x, y. */
  coseKey() {
    return concat(new Uint8Array([0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21]), cborBytes(this.x), new Uint8Array([0x22]), cborBytes(this.y));
  }

  /** The fields of POST /auth/mfa/passkeys, for the server's creation options. */
  async register(options, origin) {
    const attested = concat(new Uint8Array(16), new Uint8Array([0, this.credentialId.length]), this.credentialId, this.coseKey());
    const authData = concat(await this.authData(UP | UV | AT), attested);
    const attestationObject = concat(new Uint8Array([0xa3]), cborText('fmt'), cborText('none'), cborText('attStmt'), new Uint8Array([0xa0]), cborText('authData'), cborBytes(authData));
    return {
      clientDataJson: b64url(this.clientData('webauthn.create', options.challenge, origin)),
      attestationObject: b64url(attestationObject),
    };
  }

  /** The `assertion` of POST /auth/mfa/login, for the server's request options. */
  async assert(options, origin) {
    this.counter += 1;
    const clientData = this.clientData('webauthn.get', options.challenge, origin);
    const authData = await this.authData(UP | UV);
    const clientHash = new Uint8Array(await crypto.subtle.digest('SHA-256', clientData.buffer));
    const raw = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, this.keys.privateKey, concat(authData, clientHash).buffer));
    return {
      credentialId: this.credentialIdText,
      clientDataJson: b64url(clientData),
      authenticatorData: b64url(authData),
      signature: b64url(der(raw)),
    };
  }
}
