# NClientV3

[![Github](https://img.shields.io/github/v/release/maxwai/NClientV3.svg?logo=github)](https://github.com/maxwai/NClientV3/releases/latest)

An unofficial NHentai Android Client. This is a fork (of a fork) of the original Project by [@Dar9586](https://github.com/Dar9586) found [here](https://github.com/Dar9586/NClientV2)

Credits to [@maxwai](https://github.com/maxwai/) for the fork and many thanks for continuing the development of this project.

This app works for devices from API 26 (Android 8) and above.

For Devices Running Android 8 (SDK 26 and 27) there is a separate SDK with `pre28` in the name. Support for this Version of the app will be reduced.

Releases: <https://github.com/ILFforever/NClientV3/releases>


## STUFF ADDED
- Favorite Multi page randomizer (Instead of the original which randomizes only entries in current page).
- Fix for Random button crashing app. (By adding error return on extToString())

(I do not intent to update nor maintain this repo regularly but it does work as of 04/09/2025)
(This is my first time actually working with Android Studio, so if any functionality breaks I'm super duper sorry)

## API Features

- Browse main page
- Search by query or tags
- Include or exclude tags
- Blur or hide excluded tags
- Download manga
- Favorite galleries
- Enable PIN to access the app

## Custom feature

- Share galleries
- Open in browser
- Bookmark

## App Screen

|                                                                Main page                                                                 |                                                                Lateral menu                                                                 |
|:----------------------------------------------------------------------------------------------------------------------------------------:|:-------------------------------------------------------------------------------------------------------------------------------------------:|
| ![Main page](https://raw.githubusercontent.com/maxwai/NClientV3/master/fastlane/metadata/android/en-US/images/phoneScreenshots/img1.jpg) | ![Lateral menu](https://raw.githubusercontent.com/maxwai/NClientV3/master/fastlane/metadata/android/en-US/images/phoneScreenshots/img2.jpg) |
|                                                                  Search                                                                  |                                                                Random manga                                                                 |
|  ![Search](https://raw.githubusercontent.com/maxwai/NClientV3/master/fastlane/metadata/android/en-US/images/phoneScreenshots/img3.jpg)   | ![Random manga](https://raw.githubusercontent.com/maxwai/NClientV3/master/fastlane/metadata/android/en-US/images/phoneScreenshots/img4.jpg) |

## Contributors

- [shirokun20](https://github.com/shirokun20) for the initial Bug fixes

## Contributors of original Project

- [Still34](https://github.com/Still34) for code cleanup & Traditional Chinese translation
- [TacoTheDank](https://github.com/TacoTheDank) for XML and gradle cleanup
- [hmaltr](https://github.com/hmaltr) for Turkish translation and issue moderation
- [ZerOri](https://github.com/ZerOri) and [linsui](https://github.com/linsui) for Chinese translation
- [herrsunchess](https://github.com/herrsunchess) for German translation
- [eme22](https://github.com/herrsunchess) for Spanish translation
- [velosipedistufa](https://github.com/velosipedistufa) for Russian translation
- [bottomtextboy](https://github.com/bottomtextboy) for Arabic translation
- [MaticBabnik](https://github.com/MaticBabnik) for bug fixes
- [DontPayAttention](https://github.com/DontPayAttention) for French translation
- [kuragehimekurara1](https://github.com/kuragehimekurara1) for Japanese translation
- [chayleaf](https://github.com/chayleaf) for Cloudflare bypass
- [Atmosphelen](https://github.com/Atmosphelen) for Ukrainian translation

## Libraries

- PersistentCookieJar ([License](https://github.com/franmontiel/PersistentCookieJar/blob/master/LICENSE.txt))
- OKHttp ([License](https://github.com/square/okhttp/blob/master/LICENSE.txt))
- multiline-collapsingtoolbar ([License](https://github.com/opacapp/multiline-collapsingtoolbar/blob/master/LICENSE))
- PhotoView ([License](https://github.com/chrisbanes/PhotoView/blob/master/LICENSE))
- JSoup ([License](https://github.com/jhy/jsoup/blob/master/LICENSE))
- ACRA ([License](https://github.com/ACRA/acra/blob/master/LICENSE))
- Glide ([License](https://github.com/bumptech/glide/blob/master/LICENSE))

## License

```text
   Copyright 2024 maxwai

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
