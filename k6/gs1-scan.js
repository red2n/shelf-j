// GS1 2D barcodes at the till (07.15), through the gateway.
//
// GS1 Sunrise 2027 asks a retail till to read a GTIN from a GS1 DataMatrix and a GS1 Digital Link QR
// by 31 December 2027. The till read a scanned code as a flat string, so a DataMatrix element string
// and a Digital Link URI both found nothing — and the batch, expiry and weight such a code carries
// were thrown away.
//
// The assertion this suite exists for is the first one: the SAME item is found by every form of its
// GTIN. A shop types the EAN-13 off the shelf edge; the packet carries the GTIN-14 in its 2D code;
// the case in the stockroom carries the GTIN-14 too. Those are one product, and a string comparison
// says they are three.
//
// The rest is what a 2D code makes possible and a linear one cannot: a lot-scoped recall that stops
// the recalled lot and sells every other one, and a weight that prices a loose item with no scale.
// Refused: a misread check digit, a GTIN nobody carries, an unknown application identifier, another
// business's item by any encoding, a code that is not a code, and an anonymous caller.
//
//   k6/run.sh gs1-scan
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, sellingTenant, truthy, uniq } from './lib/shelfj.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const SCAN = '/api/product-svc/catalog/scan';

/** The GS1 modulo-10 check digit for a body of digits, weighted 3,1 from the right. */
function checkDigit(body) {
  let sum = 0;
  let weight = 3;
  for (let i = body.length - 1; i >= 0; i--) {
    sum += Number(body[i]) * weight;
    weight = weight === 3 ? 1 : 3;
  }
  return (10 - (sum % 10)) % 10;
}

/** A fresh EAN-13 nobody else holds. Prefix 7 so it cannot collide with a fixture elsewhere. */
let serial = 0;
function freshEan13() {
  serial += 1;
  const body = `7${String(Date.now() % 10000000).padStart(7, '0')}${String(serial).padStart(4, '0')}`;
  return body + checkDigit(body);
}

export default function () {
  const tag = uniq().toUpperCase().slice(0, 8);
  // sellingTenant gives a shop with a priced, stocked variant, a rival business and the two staff
  // roles the refusal checks need — so no fixture is built twice here.
  const shop = sellingTenant(`gs1-${tag}`);
  const owner = shop.tenant.owner.token;
  const scan = (code, token) => call('GET', `${SCAN}?code=${encodeURIComponent(code)}`, { token: token || shop.cashier.token });

  // A variant carrying the EAN-13 a shop would have typed from the shelf edge.
  const ean13 = freshEan13();
  const gtin14 = `0${ean13}`;
  const product = data(call('POST', '/api/product-svc/admin/products', { token: owner, body: { name: `Tinned beans ${tag}` } }));
  const variant = data(call('POST', `/api/product-svc/admin/products/${product.id}/variants`, {
    token: owner,
    body: { sku: `BEANS-${tag}`, barcode: ean13 },
  }));
  truthy('[+] the shop lists an item by the barcode on its shelf edge', !!variant.id, variant);

  // ── every form of one GTIN names one item ────────────────────────────────────────────────────────
  const forms = {
    'the linear barcode as typed': ean13,
    'the GTIN-14 a case carries': gtin14,
    'a DataMatrix element string': `01${gtin14}`,
    'the same written out by a person': `(01)${gtin14}`,
    'a Digital Link QR on GS1’s resolver': `https://id.gs1.org/01/${gtin14}`,
    'a Digital Link on the brand’s own domain': `https://shop.example.co.uk/01/${gtin14}`,
  };
  for (const [what, code] of Object.entries(forms)) {
    const res = scan(code);
    expect(res, `[+] ${what} finds the item`, 200);
    truthy(`[+] ${what} finds the SAME item`, (data(res).item || {}).variantId === variant.id, { what, got: (data(res).item || {}).variantId });
  }

  // ── what a 2D code carries besides the item ─────────────────────────────────────────────────────
  const rich = data(scan(`01${gtin14}17261231310300125010LOT-7`));
  truthy('[+] the format is named, so a till knows what it read', rich.code.format === 'ELEMENT_STRING', rich.code);
  truthy('[+] the lot the pack declared', rich.code.batch === 'LOT-7', rich.code);
  truthy('[+] the expiry as a date, not six digits', rich.code.expiry === '2026-12-31', rich.code);
  truthy('[+] the weight scaled by the AI’s own decimal places', rich.code.netWeightKg === '1.250', rich.code);

  // A day of 00 means the end of that month — what "best before end December" prints as. A till that
  // cannot read it stops a legitimate sale; one that reads it as the 1st is a month out either way.
  truthy('[+] a day of 00 is the end of the month, not an unreadable date', data(scan(`01${gtin14}15261200`)).code.bestBefore === '2026-12-31', data(scan(`01${gtin14}15261200`)).code);

  const link = data(scan(`https://id.gs1.org/01/${gtin14}/10/AB12?17=270131&3922=0899`));
  truthy('[+] a Digital Link carries its extras in the query', link.code.format === 'DIGITAL_LINK' && link.code.batch === 'AB12' && link.code.expiry === '2027-01-31', link.code);
  truthy('[+] and an amount payable, scaled', link.code.amountPayable === '8.99', link.code);
  // 3932 puts three digits of ISO 4217 in FRONT of the amount. Scaling the whole field reads GBP 12.50
  // as 8,261.25, which is the sort of error that reaches a customer's receipt.
  const money = data(scan(`01${gtin14}39328261250`));
  truthy('[+] a currency-qualified amount is the amount, not the currency digits too', money.code.amountPayable === '12.50' && money.code.currencyNumeric === '826', money.code);

  // A tracking parameter on the URI is ignored rather than fatal: marketing adds them to packaging.
  truthy('[-] an unknown query parameter does not spoil a Digital Link', (data(scan(`https://id.gs1.org/01/${gtin14}?utm_source=pack&17=261231`)).item || {}).variantId === variant.id, 'still found');

  // ── a code that is not GS1 goes on working ──────────────────────────────────────────────────────
  const internal = `SHELF-EDGE-${tag}`;
  const plainVariant = data(call('POST', `/api/product-svc/admin/products/${product.id}/variants`, {
    token: owner,
    body: { sku: `EDGE-${tag}`, barcode: internal },
  }));
  const plain = data(scan(internal));
  truthy('[+] a shop’s own internal code still finds its item', plain.item.variantId === plainVariant.id, plain.item);
  truthy('[+] and reports that the code carried nothing, rather than inventing a reading', !plain.code, plain);

  // ── what it refuses ────────────────────────────────────────────────────────────────────────────
  const misread = ean13.slice(0, 12) + (ean13[12] === '0' ? '1' : '0');
  expect(scan(misread), '[-] one digit out in the check digit finds nothing, rather than the wrong item', 404, 'VARIANT_NOT_FOUND');
  expect(scan(`01${'0' + misread}`), '[-] and the same misread inside an element string', 404, 'VARIANT_NOT_FOUND');
  const unheld = `0${freshEan13()}`;
  const missing = scan(`01${unheld}10NONE`);
  expect(missing, '[-] a GTIN nobody carries is a 404', 404, 'VARIANT_NOT_FOUND');
  truthy('[-] naming the GTIN, which a shopkeeper can act on, not the element string', String(missing.body).includes(unheld), String(missing.body).slice(0, 200));
  // An unknown AI is refused rather than skipped: skipping means guessing where its data ended, and a
  // wrong guess reads the rest of a food label as different fields — quietly.
  expect(scan(`01${gtin14}8888whatever`), '[-] an application identifier the platform does not read is refused, not skipped', 404);
  expect(scan(''), '[-] a blank code is a bad request', 400, 'INVALID_BARCODE');
  expect(scan('https://example.com/promotions/summer'), '[-] an ordinary web page is not a product', 404);

  // ── whose items these are ──────────────────────────────────────────────────────────────────────
  expect(scan(ean13, shop.rival.owner.token), '[abuse] another business does not find this one’s item by its barcode', 404);
  expect(scan(`https://id.gs1.org/01/${gtin14}`, shop.rival.owner.token), '[abuse] nor by its Digital Link — the GTIN form is not a way round the tenant filter', 404);
  // A catalogue read is anonymous only WITH the storefront header: that is what names the shop when
  // there is no token to name it. Without either there is no tenant to resolve, so the gateway asks
  // for a token rather than guessing — both halves are asserted, because the first without the second
  // would read as "anyone can scan anything".
  expect(call('GET', `${SCAN}?code=${ean13}`, { storefront: shop.tenant.tenantId }), '[+] a shopper with no token scans through the storefront header', 200);
  expect(call('GET', `${SCAN}?code=${ean13}`), '[abuse] but no token and no storefront names no shop, and is refused', 401);
  expect(call('GET', `${SCAN}?code=${ean13}`, { storefront: shop.rival.tenantId }), '[abuse] and the storefront header of another shop finds nothing of this one\u2019s', 404);

  completed.add(1);
}
