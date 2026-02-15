# TGWeb Android Native Client Skeleton

This repository now contains a multi-module Android-first client skeleton targeting Telegram parity for:
- chat sync pipeline
- push notifications (FCM service hook + background sync worker)
- offline message storage (Room + SQLCipher)
- media caching scaffold (EncryptedFile + LRU eviction placeholder)
- basic push backend API (`backend/push`)

## Modules
- `app`: Android UI shell (Compose), startup wiring, periodic sync scheduling
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
