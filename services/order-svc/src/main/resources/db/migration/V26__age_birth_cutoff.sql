-- 10.8: an age check can be judged against a date of birth as well as an age.
--
-- From 1 January 2027 nobody born on or after 1 January 2009 may be sold tobacco in the UK, however
-- old they are (Tobacco and Vapes Act 2026). The register already copies the minimum age that
-- applied; it now copies the cut-off too, and whether it was the law or the business's own earlier
-- policy. The customer's date of birth is not recorded: the defence is that the check was made
-- against the right rule, and a stored birth date would be personal data kept for no purpose.

ALTER TABLE age_verifications
    ADD COLUMN born_before        DATE,
    ADD COLUMN born_before_policy BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE age_verifications DROP CONSTRAINT chk_age_reason;
ALTER TABLE age_verifications ADD CONSTRAINT chk_age_reason CHECK (reason IS NULL OR reason IN (
    'UNDER_AGE', 'NO_ID', 'ID_REJECTED', 'PROXY_SALE', 'BORN_AFTER_CUTOFF', 'OTHER'));
-- A refusal for the date of birth names the date it was refused against.
ALTER TABLE age_verifications ADD CONSTRAINT chk_age_cutoff_reason
    CHECK (reason IS DISTINCT FROM 'BORN_AFTER_CUTOFF' OR born_before IS NOT NULL);
-- Policy describes a cut-off; there is nothing to describe without one.
ALTER TABLE age_verifications ADD CONSTRAINT chk_age_cutoff_policy
    CHECK (born_before IS NOT NULL OR NOT born_before_policy);
