-- A provider that takes the business's own credentials — India's portal wants the taxpayer's API
-- user and password, KSeF an authorisation token — keeps them here, sealed under the deployment's
-- key (storeql.einvoice.secrets-key, AES-GCM) and never read back over the API. Nothing is stored
-- until the key exists: a deployment without one cannot choose such a provider.
ALTER TABLE einvoice_transport_settings ADD COLUMN provider_secret TEXT;
