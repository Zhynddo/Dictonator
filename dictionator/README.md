# Dịch Thuật — offline translator for Android

Works with **no internet** after a one-tap setup.

- **Translate** — type, speak 🎤, take a photo 📷 or pick one from the gallery
- **Conversation** — two people, two microphones, each sees the other's words translated
- **Dictionary** — English–Vietnamese dictionary built in (Hồ Ngọc Đức / FVDP, free)
- **Saved** — starred translations + recent history
- Select text in any app → **Dịch Thuật**, or Share → Dịch Thuật

---

## For her: one button

1. Open the APK file you were sent (Zalo, Messenger, email…) and tap **Install**.
   If Android asks, tap **Settings → Allow from this source**, go back, and tap **Install**.
2. Open **Dịch Thuật**, connect to Wi-Fi, and tap **Bắt đầu · Start**.

That's all. Setup downloads the Vietnamese pack and installs the dictionary in the background
(about 40 MB, a few minutes). She can close the app. A notification says when it's ready.
If Wi-Fi isn't on, setup waits and starts by itself once it is.

---

## For the helper: build the APK once (about 10 minutes, on a computer)

1. Make a free account at **github.com**.
2. Click **+** (top right) → **New repository**. Name it `DichThuat`, choose **Private**, and click **Create repository**.
3. Click **uploading an existing file**. Unzip `DichThuat.zip`, open the `DichThuat` folder, select **everything inside it**
   (including the `.github` folder) and drag it onto the page. Click **Commit changes**.
   > **Mac hides `.github`.** After uploading the rest, click **Add file → Create new file**, name it
   > `.github/workflows/build.yml`, paste in that file's contents, and click **Commit changes**.
4. Open the **Actions** tab and wait for the green ✓ (5–8 min). A red ✗ means the build failed: open it, copy the error, and send it to Claude.
5. Open **Releases** (right side of the repo page) → download **DichThuat.apk** → send it to her.

---

## Optional extras (in phone Settings, not needed for translation)
- **Offline voice input:** search Settings for "offline speech recognition" and download Vietnamese. Without it, the 🎤 needs internet.
- **Read-aloud voice:** Settings → Text-to-speech → Google → Install voice data → Vietnamese.
- **More languages:** in the app, tap ⬇ at the top right.

### Notes
- Photo translation reads Latin-alphabet text (Vietnamese, English, French…), not Chinese, Japanese or Korean characters.
- Offline translation is good for everyday text but weaker than online Google Translate on long, complex sentences.
- Dictionary data: Free Vietnamese Dictionary Project (Hồ Ngọc Đức), GPL.
