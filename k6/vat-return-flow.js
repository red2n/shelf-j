// SJ-D39: box 4 of the VAT return is real. A supplier invoice captured in purchase-svc reaches
// pricing-svc as SupplierInvoiceCaptured and shows up in the return as input VAT (box 4) and net
// purchases (box 7), by invoice date; box 5 is what is actually owed. Another tenant's invoices are
// another tenant's return, and the return is management-only.
//
//   k6/run.sh vat-return-flow
import { ALL_CHECKS_PASS, call, data, expect, must, onboardTenant, poll, sellableVariant, staffUser, truthy } from './lib/shelfj.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

export function setup() {
  const tenant = onboardTenant('vat', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('vat-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const { variantId } = sellableVariant(tenant, 'Invoiced widget');
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, store, variantId, cashier };
}

export default function ({ tenant, rival, store, variantId, cashier }) {
  const owner = tenant.owner.token;
  const period = { from: '2026-09-01T00:00:00Z', to: '2026-10-01T00:00:00Z' };
  const vatReturn = (token) => call('GET', `/api/pricing-svc/vat-return?from=${period.from}&to=${period.to}`, { token });

  const before = vatReturn(owner);
  expect(before, 'the return reads before any invoice', 200);
  truthy('and says it is fit to file, with the boxes it computes named', data(before).fitToFile === true && JSON.stringify(data(before).computedBoxes) === '[1,3,4,5,6,7]', data(before));
  const box4Before = Number(data(before).box4);

  // ── a purchase order, invoiced ────────────────────────────────────────────
  const supplier = must(call('POST', '/api/purchase-svc/suppliers', { token: owner, body: { name: `VAT Supplier ${Date.now()}`, vatRegistered: true, currency: 'GBP' } }), 201, 'supplier');
  const po = must(call('POST', '/api/purchase-svc/purchase-orders', { token: owner, body: { supplierId: supplier.id, storeId: store.id } }), 201, 'purchase order');
  expect(call('POST', `/api/purchase-svc/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty: 10, unitPrice: '15.00' } }), 'a line on the order', 201);
  expect(call('POST', `/api/purchase-svc/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 'the order is submitted', 200);
  const invoice = call('POST', '/api/purchase-svc/supplier-invoices', { token: owner, body: { poId: po.id, invoiceNumber: `INV-${Date.now()}`, invoiceDate: '2026-09-10', vatAmount: '30.00', lines: [{ variantId, qty: 10, unitPrice: '15.00' }] } });
  expect(invoice, 'the supplier invoice is captured: net 150, VAT 30', 201);
  truthy('with its figures', Number(data(invoice).netAmount) === 150 && Number(data(invoice).vatAmount) === 30, data(invoice));

  // ── and reaches the return ────────────────────────────────────────────────
  const arrived = poll(30, () => Number(data(vatReturn(owner)).box4) === box4Before + 30);
  truthy('box 4 rises by the invoice VAT within 30 s', arrived >= 0, data(vatReturn(owner)));
  const after = data(vatReturn(owner));
  truthy('box 7 carries the net purchases', Number(after.box7) >= 150, after);
  truthy('box 5 is |box 3 - box 4|', Math.abs(Number(after.box3) - Number(after.box4)).toFixed(2) === Number(after.box5).toFixed(2), after);
  truthy("a rival tenant's return is untouched", Number(data(vatReturn(rival.owner.token)).box4) === 0, data(vatReturn(rival.owner.token)));
  expect(vatReturn(cashier.token), 'a cashier cannot read the return', 403);
  expect(call('GET', `/api/pricing-svc/vat-return?from=${period.to}&to=${period.from}`, { token: owner }), 'a period that ends before it starts is refused', 400, 'PRICING_INVALID_PERIOD');
}
