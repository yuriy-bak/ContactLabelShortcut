# Contact Label Shortcut

**English** | [Русский](README.ru.md)

[![Support on Boosty](https://img.shields.io/badge/Boosty-Support_the_app-F15F2C?logo=boosty&logoColor=white)](https://boosty.to/yuriybak)

An Android app that puts a home screen shortcut for a contact label: one tap opens all contacts with that label. Works offline.

## How it works

- When you add the shortcut, the app shows your contact labels (Google Contacts labels and groups of other accounts) and you choose one.
- The shortcut opens the label where the setting on the app's main screen says: the system choice (default contacts app, `ACTION_VIEW` on `content://com.android.contacts/groups/<id>`; Google Contacts supports this), a specific app, or the app's own list.
- The app's own list opens almost instantly. Each contact has an avatar and buttons: call (opens the dialer), SMS, and chats in messengers the contact has on this phone (WhatsApp, Telegram, Signal, Viber, Threema). Other actions of other apps are in the ⋮ menu. A tap on the name opens the contact in any contacts app. Long-press call or SMS to choose a number.
- The shortcut stores the label's server id and title, so it keeps working after the account is resynced and the local id changes.
- The only permission is reading contacts. The app makes no network requests.

## Installation

Download the APK from [Releases](https://github.com/yuriy-bak/ContactLabelShortcut/releases) and install it. Android 8.0 or later. To get updates automatically, add the repository to [Obtainium](https://github.com/ImranR98/Obtainium).

## Usage

- **From the launcher:** long-press the home screen → Widgets → Label Shortcut → drag **Contact label** to the screen → choose a label.
- **From the app:** open Label Shortcut → **Create shortcut** → choose a label → confirm adding it.

## Building

APKs are built by GitHub Actions: every push to `main` uploads an artifact, and a `v*` tag publishes a release with the APK.

Local build (JDK 17, Android SDK, Gradle 8.11):

```bash
base64 -d signing/release.jks.b64 > signing/release.jks
gradle :app:assembleRelease
```

The signing key is in the repository on purpose, so that every build, local or CI, has the same signature and updates install over each other. It is not a secret: anyone can build an APK with this signature, so install it only from this repository's releases.

## Support

If the app is useful to you, a star on GitHub helps other people find it, and you can support its development on [Boosty](https://boosty.to/yuriybak). Bugs and ideas: [Issues](https://github.com/yuriy-bak/ContactLabelShortcut/issues).

## License

MIT. See [LICENSE](LICENSE).
