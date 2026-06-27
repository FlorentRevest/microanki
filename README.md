<img src="docs/icon.png" alt="MicroAnki app icon: a buff cartoon guy holding up one finger next to a speech bubble with a star in it" width="128" align="right">

# MicroAnki

*I do one flashcard.*

Practise vocabulary **in line with your existing habits**: every time you open a
distracting app (Instagram, YouTube, Facebook, …), MicroAnki shows you one
flashcard from a deck you choose — drawn straight from your real
[AnkiDroid](https://github.com/ankidroid/Anki-Android) collection — before it
lets you through.

It uses the
[AnkiDroid API / database ContentProvider](https://github.com/ankidroid/apisample)
to fetch cards that are actually due and to report your answer back, so your
normal spaced-repetition scheduling keeps working.

## How it works

1. An **accessibility service** notices the moment one of your chosen apps comes
   to the foreground (it only reads the foreground package name — never screen
   content).
2. A full-screen **flashcard** is shown on top of that app: question → *Show
   answer* → grade it (Again / Hard / Good / Easy).
3. Grading is sent to AnkiDroid, which reschedules the card. Then you're dropped
   back into the app you opened.

A card only appears on a genuine *switch into* a trigger app, so navigating
around inside the app — or returning to it right after answering — won't spam
you. An optional cooldown rate-limits things further.

## Setup (in the app)

Open MicroAnki and work down the checklist:

1. **Permissions**
   - Install **AnkiDroid** (if you haven't).
   - Grant MicroAnki access to your AnkiDroid collection.
   - Enable the **accessibility** service (Settings → Accessibility → MicroAnki).
   - Allow **Display over other apps**.
2. **Deck** — pick the deck you want to practise.
3. **Trigger apps** — tick the apps that should show a card when opened.
4. **Options** — set a cooldown (0 = every open) and whether the Back button is
   blocked until you answer.

Tip: use *Show a card now* to test without leaving the app.

## Building

Requires the Android SDK and JDK 17+.

```bash
./gradlew assembleDebug      # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # build + install on a connected device
```

The AnkiDroid API dependency (`com.github.ankidroid:Anki-Android:api-v1.1.0`) is
pulled from JitPack — see `settings.gradle.kts`.

| | |
|---|---|
| Language | Kotlin + Jetpack Compose |
| minSdk / targetSdk | 26 / 35 |
| AnkiDroid API | `api-v1.1.0` |

## Privacy

Everything runs on-device. The accessibility service reads only which app is in
the foreground; MicroAnki never reads the content of your screen and never sends
anything off the device.
