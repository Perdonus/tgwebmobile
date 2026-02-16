# TGWeb Web K Shell

This repository is an Android shell for Telegram Web K with no native chat UI.

Core goals:
- original Telegram Web K interface in `WebView` only
- native background reliability (FCM + WorkManager + sync worker)
- offline snapshot + encrypted media cache
- bridge layer for downloads, offline state, proxy state, and push events
- push backend API with delivery metrics (`backend/push`)
- mobile shell enhancements: Android file chooser, edge-back behavior, loading animation,
  runtime interface scale, theme-adaptive system bars, keep-alive foreground service

## Modules
- `app`: Android WebView-only shell (loads bundled Web K or `https://web.telegram.org/k/`)
- `core:webbridge`: command/event contracts and in-process bridge bus
- `core:tdlib`: TDLib abstraction + stub implementation
- `core:db`: encrypted Room schema and DAO layer
- `core:data`: repository contracts + chat repository implementation
- `core:media`: media cache manager and download/export path
- `core:notifications`: notification channels, FCM service, boot receiver
- `core:sync`: WorkManager workers + scheduling helper
- `backend:push`: Ktor service with `/v1/devices/*` and health endpoints

## Build notes
- Android SDK path expected at `/usr/lib/android-sdk` in this environment.
- This skeleton uses `StubTdLibGateway`; wire real TDLib JNI integration for production.
- Add `google-services.json` and production Firebase credentials before release.
- Put a built Telegram Web K fork at `app/src/main/assets/webapp/webk/index.html` to run fully local/offline.
- You can also place upstream Web K production bundle directly in `app/src/main/assets/webapp/webk-src/public/index.html`
  (the app auto-detects both paths).
- If local Web K bundle is absent, the shell falls back to `https://web.telegram.org/k/`.
- If your environment has broken proxy variables, clone upstream with:
  `env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy git clone --depth=1 https://github.com/TelegramOrg/Telegram-web-k.git app/src/main/assets/webapp/webk-src`

## Colab build notebook
- Template notebook: `colab/tgwebmobile_colab_build_template.ipynb`
- Local runtime notebook (ignored by git): `colab/tgwebmobile_colab_build.ipynb`
