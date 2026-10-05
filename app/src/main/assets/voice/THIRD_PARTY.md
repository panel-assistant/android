# Third-party material in the voice assistant

## Wake sound

`wake_word_triggered.flac` is the wake-word sound of the Home Assistant Voice Preview Edition, taken unmodified from `esphome/home-assistant-voice-pe` at commit `d4e6fa43d6a1a7d342b7d4d403567e06516719de` (`sounds/wake_word_triggered.flac`, SHA-256 `5c26cad33931670f2b62db5d2ed35c562b162e819ade38e646680065ad1b055f`).

Home Assistant Voice Preview Edition Sounds (https://github.com/esphome/home-assistant-voice-pe/tree/dev/sounds) © 2024 by Clayton Charles Tapp (https://www.cctaudio.com/) is licensed under Creative Commons Attribution 4.0 International (https://creativecommons.org/licenses/by/4.0/).

## Listening ripple

`WakeRippleView` (`app/src/main/kotlin/io/panelassistant/android/assist/WakeRippleView.kt`) is ported from Ava-Pro's view of the same name, `knoop7/Ava-Pro` at commit `32c8abe2c68336d46f727b50e8126c098e2cdaa0` (`android/app/src/main/java/com/example/ava/ui/views/WakeRippleView.kt`), licensed under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0). Changes: the package name, trailing whitespace, and the view no longer takes touches.
