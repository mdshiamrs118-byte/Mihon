# Mihon + page translation

A Translate button in the reader's bottom bar (between reading mode and rotation) OCRs the pages from the current one
down, translates them and swaps in the translated images. Everything is configured in Settings > Translation.

- Code: `app/src/main/java/mihon/translation/`
- Reader hook: `ReaderPage.displayStream()`; translated pages are stored per chapter in `files/translations/`
- Install: GitHub > Actions > "Build APK" > Run workflow, then download the `mihon-translation-apk` artifact
  (application id `app.mihon.debug`, so it installs next to your normal Mihon)

Not compiled or run through `./gradlew check` yet. The first Actions run is the first compile; expect to fix a few errors,
and ktlint (`spotlessCheck`) will flag some long lines.
