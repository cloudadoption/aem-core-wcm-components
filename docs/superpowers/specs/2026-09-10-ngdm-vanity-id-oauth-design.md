# NGDM Vanity Asset ID via Server-Side OAuth Metadata Fetch

**Status:** Approved for demo implementation (automated tests deferred to pre-go-live)
**Date:** 2026-09-10
**Repo:** aem-core-wcm-components (`bundles/core`, `content`)

## Background

The Image v3 component already supports an opt-in "vanity asset ID" feature: instead of the
real Dynamic Media asset id (`urn:aaid:aem:<uuid>`), the delivery URL can use a human-readable
alias (`urn:avid:aem:<value>`) read from a configurable asset metadata property. This is
controlled by two existing design-dialog fields: `enableVanityId` (checkbox) and
`vanityIdMetadataProperty` (text field), which are unchanged by this design.

The previous implementation captured the vanity value by monkey-patching the shared
`window.PureJSSelectors` object in the AEM authoring dialog, intercepting the Asset Selector
library's internal `handleSelection` callback to read the full selected-asset object. This
worked for the standard `dc:description` field, but a real customer engagement (T-Mobile) hit
the same wall on a separate instance: the Asset Selector's selection payload only ever includes
a fixed, curated set of standard metadata fields (`dc:*`, `repo:*`, `tiff:*`, `dam:*`) — never
custom-namespaced properties, regardless of schema registration. Confirmed directly by an Adobe
engineer in an internal Slack thread: the out-of-the-box picker does not expose custom metadata
to the client at all; the sanctioned pattern is to make your own authenticated call to the
per-asset metadata endpoint after selection.

Separately confirmed via a real authenticated `curl` call: the full metadata (including custom
properties) genuinely is available from `/adobe/assets/{id}/metadata` — but only with a valid
IMS OAuth bearer token. Anonymous calls return a small curated subset. This was never a
namespace or schema-registration problem, purely an authentication-tier one.

This design replaces the monkey-patch with a server-side, OAuth-authenticated metadata fetch,
triggered once at asset-selection time (not at render time), so the resolved value is baked
into `fileReference` before the component is ever saved.

## Goals

- Resolve the configured vanity-id metadata property (any property name, standard or custom)
  via an authenticated call to the Dynamic Media metadata API.
- Keep the resolution client-triggered (same moment as before — asset selection in the dialog),
  but server-executed, so the OAuth client secret never reaches the browser.
- Zero added latency or external calls at image render time, on both Author and Publish —
  `fileReference` is fully resolved by save time, exactly like the previous implementation's
  end state.
- Remove the monkey-patch entirely; the client-side change becomes simpler than what it
  replaces.

## Non-goals

- Automated tests for the new Java/servlet code (explicitly deferred to before go-live).
- A resource-change listener or any other author-side background reprocessing mechanism —
  rejected in favor of the simpler request/response flow described below.
- Multi-tenant / multi-repository OAuth credential configuration (single global OSGi config is
  sufficient for now; can be revisited if a real need for per-repository credentials arises).

## Architecture

### 1. `DMOAuthService` (new OSGi service, `bundles/core`)

Single responsibility: acquire and cache an IMS OAuth Server-to-Server access token.

- OSGi config (`@ObjectClassDefinition`): `clientId` (plain string), `clientSecret`
  (Crypto-protected — stored via Sling's Crypto Support so it's encrypted at rest and only
  decrypted at runtime; portable across Cloud Service, on-prem, and AMS, unlike a Cloud
  Manager environment variable), `scope` (plain string, exact value copied from the Developer
  Console credential), `tokenEndpoint` (default `https://ims-na1.adobelogin.com/ims/token/v3`).
- `Optional<String> getAccessToken()`: returns a cached token if it hasn't reached its expiry
  buffer; otherwise performs a `client_credentials` grant POST to `tokenEndpoint`, caches the
  new token and its expiry, and returns it. Thread-safe (synchronized refresh — token exchanges
  are infrequent, roughly once per token lifetime, so contention is not a concern). Returns
  `Optional.empty()` on any failure (network error, non-2xx response, malformed response) and
  logs a warning; never throws.

### 2. `VanityIdResolverServlet` (new Sling servlet, `bundles/core`)

- Registered via `sling.servlet.paths=/bin/wcm/core/components/image/v3/vanityid`, GET method,
  requires an authenticated (non-anonymous) session — protected by AEM's standard Sling
  ACL/authentication model, not custom logic. Not meaningfully reachable from Publish since
  nothing on the publish render path ever calls it.
- Request parameters: `assetId` (the real `urn:aaid:aem:<uuid>`), `property` (the metadata
  property name to look up, matching the design-dialog's `vanityIdMetadataProperty`).
- Behavior: calls `DMOAuthService.getAccessToken()`; if absent, returns an empty/204 response.
  Otherwise calls `https://{repositoryId}/adobe/assets/{assetId}/metadata` with
  `Authorization: Bearer <token>` and `Accept: application/json`, parses the JSON response,
  extracts the named property (checking both the top level and the `_embedded` metadata
  blocks, matching the shapes already seen in this investigation), and returns
  `{ "vanityId": "<value>" }` as JSON, or an empty body if not found.
- `repositoryId` comes from the existing `NextGenDynamicMediaConfig` OSGi service, same as the
  rest of the component's NGDM integration — no new config needed for it.
- Any failure (auth failure, network error, property absent, malformed response) results in an
  empty/absent response, never an error the caller needs special-case handling for beyond
  "no value came back."

### 3. `image.js` changes (`content`)

No monkey-patching. The existing `assetselected` handler in the dialog already reads the real
`fileReference` from `input[name='./fileReference']` — AEM's own picker widget writes it there
*before* firing that event. The change is entirely inside that existing handler:

- If `vanityIdProperty` is configured (existing design-dialog wiring, unchanged) and the
  selected reference is an NGDM reference, parse the real asset id out of it and call
  `VanityIdResolverServlet` asynchronously with that id and the configured property name.
- On a successful, non-empty response: rewrite `input[name='./fileReference']` to
  `/urn:avid:aem:<value>/<assetName>`, same format as before.
- On any failure or empty response: leave `fileReference` untouched (real `aaid`) — identical
  graceful-degradation behavior to today.

This removes `patchAssetSelectorForVanityId()` and the `window.PureJSSelectors` wrapping
entirely. `findDescription()`'s property-lookup logic is reused (moved server-side into the
servlet's response parsing, same lookup shape).

### 4. `ImageImpl.java` / `NextGenDMImageURIBuilder.java`

No changes. `fileReference` is always either the real `aaid` reference or the fully-resolved
`avid` reference by the time these classes ever see it — this was already true before this
design and remains true after it.

## Data flow

1. Author picks an asset in the Image dialog's file-upload widget.
2. AEM's own picker widget resolves the selection and writes the real
   `/urn:aaid:aem:<uuid>/<name>` into the hidden `fileReference` input, then fires
   `assetselected`.
3. Our existing `assetselected` handler fires. If vanity id is enabled for this dialog, it
   calls `VanityIdResolverServlet` with the real asset id and the configured property name.
4. The servlet gets a cached (or freshly exchanged) OAuth token from `DMOAuthService`, calls
   the DM metadata endpoint, and returns the resolved property value (or nothing).
5. On a value coming back, the JS rewrites `fileReference` to the `urn:avid:aem:...` form.
6. Author saves the dialog. Whatever is in `fileReference` at that point (`aaid` or `avid`) is
   what gets persisted and later rendered — no further network calls at render time, ever.

## Error handling

Every failure mode (token exchange failure, DM API failure, property not present, network
timeout, malformed JSON) degrades to "no vanity id found" at every layer — `DMOAuthService`
returns `Optional.empty()`, the servlet returns an empty response, and the JS leaves
`fileReference` as the real `aaid`. There is no path that produces a broken image or blocks
the author from saving the dialog.

## Security

- The OAuth client secret is Crypto-protected OSGi config, read only by `DMOAuthService`,
  server-side only. It is never sent to the browser in any form.
- `VanityIdResolverServlet` only accepts calls from authenticated Author sessions, via
  standard Sling ACL protection — no anonymous access, no custom auth logic to get wrong.
- Rejected during design: storing the client secret in the design dialog or any other
  client-reachable location. Any value readable by browser JS is not a secret, regardless of
  how it's stored in markup — this was explicitly ruled out when raised.

## Testing

Deferred per explicit user decision, to be added before go-live:
- `DMOAuthServiceTest`: mocked `HttpClientBuilderFactory`, verifying token exchange, caching,
  and expiry-triggered refresh.
- `VanityIdResolverServletTest`: mocked `DMOAuthService` and HTTP client, verifying the
  property-extraction logic against the known response shapes (top-level and `_embedded`),
  and the empty-response fallback paths.
- `image.js` change: manual browser verification only, consistent with how the rest of this
  component's client-side behavior has been verified throughout this project — no existing JS
  test harness to extend.

## Rollback

Before this rework began, the previous (working) monkey-patch implementation was captured as
a diff patch and the full state was recorded in project memory (`ngdm-vanity-id-poc.md`, entry
dated 2026-09-10). Nothing was committed to git at that point, so the working tree itself
remains the primary rollback path if this design needs to be abandoned mid-implementation.
