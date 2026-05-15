# Security Policy

## Reporting a Vulnerability

If you discover a security issue in EasyReader, please report it privately so we can address it before public disclosure.

- **Preferred:** Open a [GitHub Security Advisory](https://github.com/Aatricks/EasyReader/security/advisories/new) on this repository.
- **Fallback:** Open an issue marked `security` with a high-level description only; we will reach out for details.

Please do **not** disclose the vulnerability publicly until we have published a fix.

## Scope

EasyReader is a client-side Android app that fetches web content from third-party sources. The defended surfaces are:

| Surface | Defenses |
|--------|----------|
| Outbound HTTP(S) | `UrlSecurity` blocks loopback, private (RFC 1918), CGNAT, link-local, multicast, and IPv4-mapped IPv6 addresses |
| DNS | `SafeDns` rejects any hostname whose A/AAAA records resolve to an unsafe IP |
| HTTP redirects | `SafeRedirectInterceptor` validates every 3xx target; max 20 hops |
| Cloudflare WebView | `WebViewUtils` disables `file://`, `content://`, mixed content, and (optionally) restricts navigation to the originating source host |
| Deep-link intents | `MainActivity.handleIntent` routes URLs through `UrlSecurity` before opening |
| Backups | `data_extraction_rules.xml` keeps reading history and library data device-local; only device-to-device transfer is permissive |

In-scope reports include but are not limited to:
- SSRF / DNS rebinding bypasses
- Deep-link or intent-filter abuse
- WebView origin escapes during Cloudflare challenges
- Local file disclosure
- Permission escalation through file-pick / content-resolver flows
- Data leakage via backup, logs, or shared preferences

Out of scope: vulnerabilities in upstream sites we scrape, in third-party libraries already tracked by their own advisories, or in non-default build flavors that ship dependencies outside our control.

## Disclosure Timeline

We aim to:
- Acknowledge a report within 7 days.
- Provide an initial assessment within 14 days.
- Ship a fix in the next regular release (typically within 30 days), or sooner for critical issues.

## Hall of Fame

We will credit reporters in release notes unless they request anonymity.
