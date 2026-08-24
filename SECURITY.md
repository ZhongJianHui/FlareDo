# Security Policy

A Simplified Chinese version is available in [SECURITY.zh-CN.md](SECURITY.zh-CN.md).

## Supported code

FlareDo currently develops and publishes only `main`. Security fixes target the latest commit on `main`; older commits and third-party builds may no longer contain current protections. The project does not currently publish signed installers or app-store releases.

## Report a vulnerability privately

Please use [GitHub Private Vulnerability Reporting](https://github.com/ZhongJianHui/FlareDo/security/advisories/new). Do **not** open a public issue or discussion, and do not post exploit details, credentials, cookies, CSRF tokens, user API keys, private forum content, personal data, or unredacted logs.

Include only the minimum information needed to reproduce and assess the issue:

- affected commit and platform/OS version;
- affected component and security impact;
- deterministic reproduction steps or a small proof of concept;
- whether authentication or user interaction is required;
- any suggested mitigation;
- sanitized logs or screenshots, if essential.

Do not test against accounts, content, or systems you do not own or have explicit permission to use. Do not perform destructive testing, denial of service, social engineering, credential collection, privacy invasion, or automated production writes against Linux.do. Prefer the repository's fake services and synthetic fixtures.

## What to expect

Maintainers will use the private advisory to acknowledge and triage the report, request clarification when necessary, coordinate a fix, and discuss disclosure. Please keep the report confidential until maintainers confirm that a correction has been published or agree to another disclosure plan. We will credit reporters who request attribution, unless doing so would expose sensitive information.

## Scope

Relevant reports include, but are not limited to:

- authorization callback, RSA payload, nonce, replay, or Android intent-validation bypasses;
- cookie, CSRF, session-generation, or same-origin isolation failures;
- credential-vault plaintext fallback, cross-account disclosure, or incomplete logout cleanup;
- unsafe cooked-HTML, URI, upload, or WebView handling;
- MessageBus authorization or cursor behavior that exposes protected data;
- sensitive-data leakage through logs, caches, CI artifacts, or diagnostics;
- dependency or packaging behavior that is exploitable in FlareDo's supported configuration.

Linux.do service vulnerabilities, forum moderation concerns, and upstream library vulnerabilities without a demonstrated FlareDo impact are outside this repository's control. Report those to the responsible service or upstream project. FlareDo is an unofficial client and is not affiliated with Linux.do or DimensionDev.
