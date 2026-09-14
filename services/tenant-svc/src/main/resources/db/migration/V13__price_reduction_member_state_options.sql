-- 03.12: the member-state options in Directive 98/6/EC art.6a.
--
-- V9 seeded the 30-day prior price for the EU. Two of its paragraphs are options a member state takes
-- up or not: art.6a(5), a reduction increased step by step keeps the price before its first step as
-- its prior price; and art.6a(3), different rules for goods that spoil or expire quickly. pricing-svc
-- applies neither unless every country whose law reaches the offer has taken it up, so a country is
-- listed here only where its own law says so. Germany's Preisangabenverordnung 2022 takes up both.
-- Another member state is added when its own implementing law has been read, never by analogy.

INSERT INTO legal_obligations (code, scope_kind, scope, effective_from, effective_to, citation, summary) VALUES
 ('PRICE_REDUCTION_PROGRESSIVE','COUNTRY','DE',DATE '2022-05-28',NULL,
  'Preisangabenverordnung 2022 §11(3), under Directive 98/6/EC art.6a(5)',
  'A price reduction increased step by step without a break is announced against the lowest price of the 30 days before its first step.'),
 ('PRICE_REDUCTION_PERISHABLE_EXEMPT','COUNTRY','DE',DATE '2022-05-28',NULL,
  'Preisangabenverordnung 2022 §11(4), under Directive 98/6/EC art.6a(3)',
  'Goods that spoil quickly or are near their expiry, reduced because of it, need no prior price when the reason is made clear.');
