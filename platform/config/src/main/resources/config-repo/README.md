# Central config repository

The config service serves the non-secret configuration in this directory. Each business service
fetches its config at startup via `common-service`'s `ConfigServiceConfigSource` (activated when
`shelfj.config.url` is set) and layers it **over** the values baked into that service's
`src/main/resources/META-INF/microprofile-config.properties`.

## File naming

For a service named `<service>` (e.g. `iam-svc`) and a profile `<profile>` (default `default`), the
service loads, in order (later overrides earlier):

```
<service>.properties            # base — applies to every profile
<service>-<profile>.properties  # profile overlay (e.g. iam-svc-docker.properties)
```

Missing files are simply skipped; if neither exists the service boots on its local defaults.

## Precedence (lowest → highest)

```
local microprofile-config.properties (ordinal 100)
  → config-svc values from this repo   (ordinal 150)   ← you are here
    → environment variables            (ordinal 300)
      → system properties              (ordinal 400)
```

So config-svc overrides the image's baked defaults, while deploy-time env/secrets still win.

## Rules

- **Non-secret values only.** Secrets (DB passwords, JWT secret, config token) come from the
  environment / a secret store, never from a committed file here.
- Keys use the same dotted names as `microprofile-config.properties`, e.g.
  `shelfj.order.pending-sweeper.ttl-hours=48`.
- Changing a file here takes effect on the next service start — no image rebuild.

## Example

To raise iam-svc's access-token TTL only in the `docker` profile, create
`iam-svc-docker.properties`:

```properties
shelfj.jwt.access-ttl-seconds=1800
```
