# Headset failure instrumentation

Use a disposable headless x86_64 Android emulator with API 29 or newer for this check.
The proof activity and instrumentation are included only in debug APKs.

Run the authored-source JVM tests with `./gradlew :testDebugUnitTest`.
Build an authored debug APK with `./gradlew -PphoneAbis=x86_64 :assembleDebug`, using the dependency sources documented in [BUILDING.md](../../BUILDING.md).
Distribution APKs still follow the projection wrapper in that document.

After installing the debug APK on the emulator, run:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  org.kde.kdeconnect_tp.debug/org.kde.kdeconnect.CodecFailureInstrumentation
```

The instrumentation creates a real HEVC MediaCodec and deliberately calls the production receiver with that codec still unconfigured.
It checks that the error returns the fatal result, is shown identically in both eyes, and leaves HEVC selected until the person chooses “Use H.264”.
It then presses that actual dialog action and checks the preference change.
The previous codec preference is restored after the check.
A successful run prints `PASS: real HEVC decoder error` and `INSTRUMENTATION_CODE: -1`.

The application's private files contain `hevc-error-sbs.png` and `hevc-choice.png`.
For example, retrieve the paired capture with:

```sh
adb -s emulator-5554 exec-out run-as org.kde.kdeconnect_tp.debug \
  cat files/hevc-error-sbs.png > hevc-error-sbs.png
```

This exercises the production receiver, diagnostic formatter, paired failure view and explicit choice dialog.
It does not establish a live RDP connection or certify a physical headset's decoder.

## Application startup and print notifications

Run `StartupPrintInstrumentation` on fresh headless x86_64 emulators at API 24, 28 and 29.
It waits for the real manifest Application's `onCreate` to return; it does not replace the Application.
The app declares minSdk 24, while the embedded FreeRDP module declares 23.

Install the authored debug APK, create Download before Application startup, and grant the declared storage permissions on the disposable emulator:

```sh
adb -s emulator-5554 shell mkdir -p /sdcard/Download
adb -s emulator-5554 shell pm grant org.kde.kdeconnect_tp.debug android.permission.READ_EXTERNAL_STORAGE
adb -s emulator-5554 shell pm grant org.kde.kdeconnect_tp.debug android.permission.WRITE_EXTERNAL_STORAGE
adb -s emulator-5554 shell am instrument -w -r -e mode startup \
  org.kde.kdeconnect_tp.debug/org.kde.kdeconnect.StartupPrintInstrumentation
adb -s emulator-5554 shell am instrument -w -r -e mode print \
  org.kde.kdeconnect_tp.debug/org.kde.kdeconnect.StartupPrintInstrumentation
```

Each invocation must report `PASS: real KdeConnect Application` and `INSTRUMENTATION_CODE: -1`.
The print mode creates an unrelated file and checks that no notification appears.
It then closes a synthetic `rdp_print_*.pdf` file written by the application process and checks the Application-owned observer's notification, filename, content intent and two actions.
On API 26 and later it also checks the notification channel.
It removes its files and notifications afterwards.
This tests the file-close-to-notification path; it does not exercise a remote RDP print job, open the PDF or dispatch a physical print job.

For the API-24 Google APIs revision-27 image with emulator 37.3.6, use 2 GiB guest RAM and `-feature -GLDMA`.
The inherited 4 GiB graphics configuration panicked inside that old guest kernel before testing the app.
All runs remain headless with KVM, two guest cores and no network access outside the container.
