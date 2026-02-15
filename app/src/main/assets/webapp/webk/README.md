Place the built Telegram Web K bundle in this directory.

Expected entrypoint:
- `app/src/main/assets/webapp/webk/index.html`

At runtime, the app loads:
1. local bundled Web K (`file:///android_asset/webapp/webk/index.html`) if present
2. otherwise fallback to `https://web.telegram.org/k/`
