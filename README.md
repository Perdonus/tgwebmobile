# TGWeb WebView Shell

This repository contains a Telegram-Web-style Android client architecture:
- Web UI runtime in Android `WebView` (bundle in `app/src/main/assets/webapp`)
- bridge bus between Web and native (`core:webbridge`)
- native background reliability (FCM + WorkManager + TDLib sync stubs)
- offline snapshot + encrypted media cache (Room/SQLCipher + EncryptedFile)
- push backend API with health and delivery metrics (`backend/push`)

## Modules
- `app`: Android WebView shell, bridge binding, bootstrap injection
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

## Colab build notebook
- Template notebook: `colab/tgwebmobile_colab_build_template.ipynb`
- Local runtime notebook (ignored by git): `colab/tgwebmobile_colab_build.ipynb`
