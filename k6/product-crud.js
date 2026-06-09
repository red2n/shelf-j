import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl } from './common.js';

export const options = { vus: 1, iterations: 1 };

const JSON_CT = { 'Content-Type': 'application/json' };

function setupTenant() {
  const email = `k6-prod-${Date.now()}@example.com`;
  const regRes = http.post(
    `${baseUrl}/api/iam-svc/auth/register`,
    JSON.stringify({ email, password: 'TestPass1!' }),
    { headers: JSON_CT }
  );
  if (regRes.status < 200 || regRes.status >= 300) return null;
  let uid = null;
  try {
    const token = regRes.json('data.accessToken');
    uid = JSON.parse(atob(token.split('.')[1])).sub;
  } catch (_) {}

  const tenantRes = http.post(
    `${baseUrl}/api/tenant-svc/onboarding/tenants`,
    JSON.stringify({ businessName: `k6-prod-co-${Date.now()}`, legalName: 'k6 Ltd', country: 'US', currency: 'USD' }),
    { headers: { ...JSON_CT, 'X-User-Id': uid || '00000000-0000-0000-0000-000000000001' } }
  );
  const tenantId = tenantRes.status < 300 ? tenantRes.json('data.id') : null;
  return { uid, tenantId };
}

export default function () {
  const ctx = setupTenant();
  const tenantId = ctx && ctx.tenantId;
  const hdrs = tenantId ? { ...JSON_CT, 'X-Tenant-Id': tenantId } : { ...JSON_CT };
  const noTenant = { ...JSON_CT };

  // ── Brands ────────────────────────────────────────────────────────────────

  const brandRes = http.post(
    `${baseUrl}/api/product-svc/admin/brands`,
    JSON.stringify({ name: `k6-brand-${Date.now()}` }),
    { headers: hdrs }
  );
  check(brandRes, { '[+] create brand 201': (r) => r.status === 201 });
  const brandId = brandRes.status === 201 ? brandRes.json('data.id') : null;

  const listBrandsRes = http.get(`${baseUrl}/api/product-svc/admin/brands`, { headers: hdrs });
  check(listBrandsRes, { '[+] list brands 200': (r) => r.status === 200 });

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/brands`,
      JSON.stringify({}),
      { headers: hdrs }
    ),
    { '[-] create brand missing name 400': (r) => r.status === 400 }
  );

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/brands`,
      JSON.stringify({ name: 'no-tenant-brand' }),
      { headers: noTenant }
    ),
    { '[-] create brand no tenant 401': (r) => r.status === 401 }
  );

  // ── Categories ────────────────────────────────────────────────────────────

  const catRes = http.post(
    `${baseUrl}/api/product-svc/admin/categories`,
    JSON.stringify({ name: `k6-cat-${Date.now()}` }),
    { headers: hdrs }
  );
  check(catRes, { '[+] create category 201': (r) => r.status === 201 });
  const categoryId = catRes.status === 201 ? catRes.json('data.id') : null;

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/categories`,
      JSON.stringify({}),
      { headers: hdrs }
    ),
    { '[-] create category missing name 400': (r) => r.status === 400 }
  );

  // ── Products ──────────────────────────────────────────────────────────────

  const prodRes = http.post(
    `${baseUrl}/api/product-svc/admin/products`,
    JSON.stringify({
      name: `k6-prod-${Date.now()}`,
      description: 'k6 test product',
      brandId: brandId,
      categoryId: categoryId,
      sellableOnline: true,
      sellablePos: true
    }),
    { headers: hdrs }
  );
  check(prodRes, { '[+] create product 201': (r) => r.status === 201 });
  const productId = prodRes.status === 201 ? prodRes.json('data.id') : null;

  if (productId) {
    const getRes = http.get(`${baseUrl}/api/product-svc/catalog/products/${productId}`, { headers: hdrs });
    check(getRes, { '[+] get product 200': (r) => r.status === 200 });

    const putRes = http.put(
      `${baseUrl}/api/product-svc/admin/products/${productId}`,
      JSON.stringify({ name: 'k6-updated', description: 'updated', sellableOnline: true, sellablePos: false }),
      { headers: hdrs }
    );
    check(putRes, { '[+] update product 200': (r) => r.status === 200 });

    const listAdminRes = http.get(`${baseUrl}/api/product-svc/admin/products`, { headers: hdrs });
    check(listAdminRes, { '[+] list products admin 200': (r) => r.status === 200 });
  }

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/products`,
      JSON.stringify({ description: 'no name' }),
      { headers: hdrs }
    ),
    { '[-] create product missing name 400': (r) => r.status === 400 }
  );

  check(
    http.get(
      `${baseUrl}/api/product-svc/catalog/products/00000000-0000-0000-0000-000000000000`,
      { headers: hdrs }
    ),
    { '[-] get product unknown 404': (r) => r.status === 404 }
  );

  // ── Variants ──────────────────────────────────────────────────────────────

  let variantId = null;
  if (productId) {
    const varRes = http.post(
      `${baseUrl}/api/product-svc/admin/products/${productId}/variants`,
      JSON.stringify({ sku: `SKU-K6-${Date.now()}`, unit: 'EA' }),
      { headers: hdrs }
    );
    check(varRes, { '[+] create variant 201': (r) => r.status === 201 });
    variantId = varRes.status === 201 ? varRes.json('data.id') : null;

    if (variantId) {
      const listVarRes = http.get(
        `${baseUrl}/api/product-svc/admin/products/${productId}/variants`,
        { headers: hdrs }
      );
      check(listVarRes, { '[+] list variants 200': (r) => r.status === 200 });
    }

    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/${productId}/variants`,
        JSON.stringify({ unit: 'EA' }),
        { headers: hdrs }
      ),
      { '[-] create variant missing sku 400': (r) => r.status === 400 }
    );
  }

  sleep(0.3);

  // ── UOM ───────────────────────────────────────────────────────────────────

  const uomClassRes = http.get(`${baseUrl}/api/product-svc/admin/uom/classes`, { headers: hdrs });
  check(uomClassRes, { '[+] list uom classes 200': (r) => r.status === 200 });

  const uomUnitsRes = http.get(`${baseUrl}/api/product-svc/admin/uom/units?classCode=QUANTITY`, { headers: hdrs });
  check(uomUnitsRes, { '[+] list uom units 200': (r) => r.status === 200 });

  if (variantId) {
    const convRes = http.post(
      `${baseUrl}/api/product-svc/admin/uom/item-conversions`,
      JSON.stringify({ variantId, fromUom: 'DOZ', toUom: 'EA', factor: 12 }),
      { headers: hdrs }
    );
    check(convRes, { '[+] create item uom conversion 2xx': (r) => r.status >= 200 && r.status < 300 });

    const listConvRes = http.get(
      `${baseUrl}/api/product-svc/admin/uom/item-conversions?variantId=${variantId}`,
      { headers: hdrs }
    );
    check(listConvRes, { '[+] list item conversions 200': (r) => r.status === 200 });

    const convertRes = http.get(
      `${baseUrl}/api/product-svc/admin/uom/convert?variant=${variantId}&from=DOZ&to=EA&qty=2`,
      { headers: hdrs }
    );
    check(convertRes, { '[+] convert uom 200': (r) => r.status === 200 });
  }

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/uom/item-conversions`,
      JSON.stringify({ variantId: '00000000-0000-0000-0000-000000000000', toUom: 'EA', factor: 12 }),
      { headers: hdrs }
    ),
    { '[-] item conversion missing fromUom 400': (r) => r.status === 400 }
  );

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  // [+] Create template
  const tplRes = http.post(
    `${baseUrl}/api/product-svc/admin/item-templates`,
    JSON.stringify({ name: `k6-tpl-${Date.now()}`, description: 'k6 test template', attributes: '{"color":"red","size":"M"}' }),
    { headers: hdrs }
  );
  check(tplRes, { '[+] create item template 201': (r) => r.status === 201 });
  const templateId = tplRes.status === 201 ? tplRes.json('data.id') : null;

  // [+] List templates
  check(
    http.get(`${baseUrl}/api/product-svc/admin/item-templates`, { headers: hdrs }),
    { '[+] list item templates 200': (r) => r.status === 200 }
  );

  if (templateId) {
    // [+] Get template by id
    check(
      http.get(`${baseUrl}/api/product-svc/admin/item-templates/${templateId}`, { headers: hdrs }),
      { '[+] get item template 200': (r) => r.status === 200 }
    );

    // [+] Apply template to variant (copies attributes onto variant)
    if (variantId) {
      const applyRes = http.post(
        `${baseUrl}/api/product-svc/admin/item-templates/${templateId}/apply/${variantId}`,
        null,
        { headers: hdrs }
      );
      check(applyRes, { '[+] apply template to variant 200': (r) => r.status === 200 });
    }

    // [+] Deactivate template
    check(
      http.del(`${baseUrl}/api/product-svc/admin/item-templates/${templateId}`, null, { headers: hdrs }),
      { '[+] deactivate item template 200': (r) => r.status === 200 }
    );
  }

  // [-] Create template duplicate name
  const tplName2 = `k6-tpl2-${Date.now()}`;
  http.post(
    `${baseUrl}/api/product-svc/admin/item-templates`,
    JSON.stringify({ name: tplName2 }),
    { headers: hdrs }
  );
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/item-templates`,
      JSON.stringify({ name: tplName2 }),
      { headers: hdrs }
    ),
    { '[-] duplicate template name 409': (r) => r.status === 409 }
  );

  // [-] Create template missing name → 400
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/item-templates`,
      JSON.stringify({ description: 'no name' }),
      { headers: hdrs }
    ),
    { '[-] create template missing name 400': (r) => r.status === 400 }
  );

  // [-] Get unknown template → 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/item-templates/00000000-0000-0000-0000-000000000000`,
      { headers: hdrs }
    ),
    { '[-] get unknown template 404': (r) => r.status === 404 }
  );

  // [-] Apply unknown template to variant → 404
  if (variantId) {
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/item-templates/00000000-0000-0000-0000-000000000000/apply/${variantId}`,
        null,
        { headers: hdrs }
      ),
      { '[-] apply unknown template 404': (r) => r.status === 404 }
    );
  }

  // [-] No tenant header on templates
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/item-templates`,
      JSON.stringify({ name: 'no-tenant-tpl' }),
      { headers: noTenant }
    ),
    { '[-] create template no tenant 401': (r) => r.status === 401 }
  );

  // ── Item Revisions (Gap #12) ──────────────────────────────────────────────

  if (variantId) {
    // [+] Create first revision
    const rev1Res = http.post(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
      JSON.stringify({ revision: 'A', description: 'Initial release', effectiveDate: '2024-01-01' }),
      { headers: hdrs }
    );
    check(rev1Res, { '[+] create revision A 201': (r) => r.status === 201 });
    const rev1Id = rev1Res.status === 201 ? rev1Res.json('data.id') : null;

    // [+] Create second revision (supersedes first)
    const rev2Res = http.post(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
      JSON.stringify({ revision: 'B', description: 'Updated spec', effectiveDate: '2024-06-01' }),
      { headers: hdrs }
    );
    check(rev2Res, { '[+] create revision B 201 (supersedes A)': (r) => r.status === 201 });
    const rev2Id = rev2Res.status === 201 ? rev2Res.json('data.id') : null;

    // [+] List all revisions
    const listRevRes = http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
      { headers: hdrs }
    );
    check(listRevRes, {
      '[+] list revisions 200': (r) => r.status === 200,
      '[+] list revisions returns array': (r) => {
        try { return Array.isArray(r.json('data')); } catch (_) { return false; }
      }
    });

    // [+] Current revision (latest effective as of today)
    const curRevRes = http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions/current`,
      { headers: hdrs }
    );
    check(curRevRes, { '[+] current revision 200': (r) => r.status === 200 });

    // [+] Get revision by ID
    if (rev1Id) {
      const getRevRes = http.get(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions/${rev1Id}`,
        { headers: hdrs }
      );
      check(getRevRes, { '[+] get revision by id 200': (r) => r.status === 200 });
    }

    // [-] Duplicate revision label (same variant + revision = 409)
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ revision: 'B', description: 'Duplicate', effectiveDate: '2025-01-01' }),
        { headers: hdrs }
      ),
      { '[-] duplicate revision 409': (r) => r.status === 409 }
    );

    // [-] Missing revision field → 400
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ description: 'no revision label', effectiveDate: '2025-01-01' }),
        { headers: hdrs }
      ),
      { '[-] create revision missing revision 400': (r) => r.status === 400 }
    );

    // [-] Missing effectiveDate → 400
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ revision: 'C', description: 'no date' }),
        { headers: hdrs }
      ),
      { '[-] create revision missing effectiveDate 400': (r) => r.status === 400 }
    );

    // [-] Invalid date format → 400
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ revision: 'D', effectiveDate: 'not-a-date' }),
        { headers: hdrs }
      ),
      { '[-] create revision invalid date 400': (r) => r.status === 400 }
    );
  }

  // [-] Revisions for unknown variant → empty list (200) or 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/00000000-0000-0000-0000-000000000000/revisions`,
      { headers: hdrs }
    ),
    { '[-] list revisions unknown variant 200 or 404': (r) => r.status === 200 || r.status === 404 }
  );

  // [-] Current revision for unknown variant → 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/00000000-0000-0000-0000-000000000000/revisions/current`,
      { headers: hdrs }
    ),
    { '[-] current revision unknown variant 404': (r) => r.status === 404 }
  );

  // [-] No tenant header on revisions endpoint
  if (variantId) {
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ revision: 'Z', effectiveDate: '2025-01-01' }),
        { headers: noTenant }
      ),
      { '[-] create revision no tenant 401': (r) => r.status === 401 }
    );
  }
}
