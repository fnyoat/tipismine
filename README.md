<div align="center">

<img src="docs/icon.svg" alt="TipIsMine" width="96"/>

# TipIsMine

[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](LICENSE)

**[中文 (Chinese)](README_cn.md)**

</div>

Regain control of the **"This device is owned by … and connected to the internet via …"** VPN prompt in Android SystemUI. Show the parts you want, hide the parts you don't, or replace the whole line with your own text — TipIsMine is an LSPosed / Xposed module that puts that banner fully in your hands.

## Quick Start

1. Install the APK that matches your framework (see **Which APK?** below).
2. Enable the module in LSPosed with scope `com.android.systemui`.
3. Open the module's config page and set your owner / VPN text.
4. Restart SystemUI (or reboot).

Config changes apply automatically — no restart needed each time.

## Which APK?

- **modern** — for new LSPosed (libxposed API 102), Android 8+. Recommended for most users.
- **legacy** — for old Xposed / old LSPosed, Android 7+.

Both share the same package id, so enable only one of them.

## What It Can Do

- **Per-slot control** — owner and VPN are independent: force each to **show**, **hide**, or keep the system **default**.
- **Whole-sentence replacement** — write your own text to replace the entire line (highest priority).
- **Live preview** — the config page previews the result in real time.
- **Dynamic content** — embed placeholders in your text for runtime evaluation: battery level, charging state, WiFi name, Bluetooth device, current time, current date, and HTTP functions get(url) / post(url, body). Parentheses, logic operators (AND / OR / NOT), comparisons, ternaries, and addition or string concatenation can all be freely nested. Requires **Expression injection** to be enabled in Settings — when it's off, placeholders are rendered verbatim and never injected into SystemUI.
- **Optional HTTP API** — update config remotely or from scripts (details in the Developer section).

Example whole-line text: `Managed by xx, connected via yy, battery ${battery}%`

Non-intrusive by design: it never touches ownership checks, and when there's no VPN it never invents a prompt.

## FAQ

- **Banner shows red "Module not activated"** — the module isn't active in LSPosed; check the scope includes `com.android.systemui`.
- **"Refresh speed" box is gray** — your text has no dynamic placeholders, so there's nothing to refresh.
- **Display options explained** — Default: follows system behavior (shows when the system shows, hides when it doesn't); custom text only replaces content, not visibility. Show: forces the slot to display with your custom text. Hide: forces the slot to not display. Follow main (lock screen only): inherits the main page's setting for that slot.

## License

[Mozilla Public License 2.0](LICENSE)

---

# Developer

## Build & Test

Requires Android SDK + JDK 17. The Gradle wrapper jar is not committed; run `gradle wrapper` once or open with Android Studio.

```bash
gradle wrapper
gradle testLegacyDebugUnitTest testModernDebugUnitTest   # fast JVM unit tests
gradle assembleLegacyRelease assembleModernRelease        # both release APKs
```

The `legacy` and `modern` APKs are built from one source via product flavors. Unit tests are pure JVM (no Android framework dependency), covering `OwnershipKeys`, `SlotMode`, `ComposePrompt`, `PromptRewriter`, and `ExpressionEngine`.

## Architecture

Everything is done by rewriting the strings that SystemUI's `Resources.getString` returns. The module never touches `hasDeviceOwner` / `hasProfileOwner` or any underlying ownership checks.

When `getString(int)` / `getString(int, Object[])` is called, the module checks the resource belongs to `com.android.systemui` and its name is in the ownership prompt key set (`OwnershipKeys`), then decides: whole-sentence replacement → slot intervention → system default.

The "match key → derive result from config" logic lives in `PromptRewriter`, shared by both flavors. Only the hook shims differ: legacy uses `XposedHelpers.findAndHookMethod`, modern uses `framework.hook(...).setExceptionMode(PROTECTIVE).intercept(Hooker)`. Any hook failure is caught and logged — no SystemUI crashes.

## Network API (Optional)

When enabled, the module runs a lightweight HTTP server on the device.

**Endpoint:** `POST /config`

**Headers:** `Content-Type: application/json`, `Authorization: Bearer <api_key>` (no auth when `api_key` is empty)

**Body:** JSON with config keys: `owner_mode`, `owner_text`, `vpn_mode`, `vpn_text`, `rewrite_whole`, `whole_text`, `expose_api`, `api_port`, `api_key`, `refresh_interval`.

```bash
curl -X POST http://<phone-ip>:8080/config \
  -H "Content-Type: application/json" \
  -d '{"owner_mode":"show","owner_text":"CompanyA","vpn_mode":"default"}'
```