-- 05.10: whether the order named anyone the notice could be written to, fixed when the notice was
-- issued. It used to be read off the status, which moves: an anonymous till sale that staff settle
-- at the counter stops being UNIDENTIFIED, and the count of buyers told grew by one who was never
-- written to. The number a buyer left is forgotten with their erasure; that they were told is not.
ALTER TABLE recall_notices ADD COLUMN buyer_identified BOOLEAN;
UPDATE recall_notices SET buyer_identified = (status <> 'UNIDENTIFIED');
ALTER TABLE recall_notices ALTER COLUMN buyer_identified SET NOT NULL;
