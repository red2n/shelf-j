-- 03.13: the EU's unit-price rule, which V9 left out.
--
-- V9 seeded the UK's amended Price Marking Order as UNIT_PRICING for GB and the EU's 30-day prior
-- price rule, but not the EU rule the prior-price rule was added to: Directive 98/6/EC art.3
-- requires the selling price and the unit price of products offered to consumers to be indicated,
-- per kilogram, litre, metre, square metre or cubic metre, or per item for goods sold by number.
-- Member states had to apply it from 18 March 2000. Without this row a German or French business
-- would be told it owes no unit price, and the unit price would quietly not be shown where it is
-- law. The same code as the UK's, so a service asks one question for any market.

INSERT INTO legal_obligations (code, scope_kind, scope, effective_from, effective_to, citation, summary) VALUES
 ('UNIT_PRICING','REGIME','EU',DATE '2000-03-18',NULL,'Directive 98/6/EC art.3',
  'The selling price and the unit price of a product offered to consumers are shown, per kilogram, litre, metre, square metre or cubic metre, or per item for goods sold by number.');
