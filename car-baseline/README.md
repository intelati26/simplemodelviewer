# Car baseline: Google's Car App Library Showcase

This folder is the **Showcase** sample from Google's Car App Library samples, copied unchanged from
[github.com/android/car-samples](https://github.com/android/car-samples) (`car_app_library/`, commit `9be0b199986770cd5cde7765765c0eaef0758d15`,
2026-05-18), with only its `showcase` modules kept: `settings.gradle` drops the `navigation` sample. It is licensed under the Apache License 2.0
(`LICENSE` here); it is not part of the simplemodelview app.

It is here as a **baseline** for Android Auto: a car app made by Google, sideloaded the same way as ours. CI builds its debug APK on manual runs of the Android
workflow (artifact `google-car-showcase-apk`). If Android Auto lists Showcase and not simplemodelview, the difference is in our app; if it lists neither,
Android Auto on the phone is not taking apps from unknown sources. See `docs/android-auto.md`.

Building it by hand: `gradle -p car-baseline :showcase:mobile:assembleDebug` with Gradle 8.7 and JDK 17 (the sample has no Gradle wrapper).
