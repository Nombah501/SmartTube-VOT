# SmartTube-VOT

**Персональный форк [SmartTube](https://github.com/yuliskov/SmartTube) со встроенным закадровым переводом видео на русский (Яндекс VOT).**

[![Release](https://img.shields.io/github/v/release/Nombah501/SmartTube-VOT)](https://github.com/Nombah501/SmartTube-VOT/releases/latest)
[![License](https://img.shields.io/github/license/Nombah501/SmartTube-VOT)](#лицензия)

> **EN summary:** personal fork of SmartTube (Android TV YouTube client) with built-in
> Russian voice-over translation (Yandex VOT), QR-code sign-in and in-app updates from
> this repository. Unofficial Yandex API, no warranty — see [disclaimers](#дисклеймеры).

---

## Об этом форке

База — открытый плеер [SmartTube](https://github.com/yuliskov/SmartTube) от
[yuliskov](https://github.com/yuliskov). Поверх него добавлен закадровый перевод:
реализация взята из [PR #5817](https://github.com/yuliskov/SmartTube/pull/5817)
(mikhkz), который в свою очередь опирается на
[voice-over-translation](https://github.com/ilyhalight/voice-over-translation),
[vot-cli](https://github.com/FOSWLY/vot-cli) и
[vot.js](https://github.com/FOSWLY/vot.js) — подробности в [CREDITS.md](CREDITS.md).

**Что изменено относительно апстрима:**

- закадровый перевод (Яндекс VOT) прямо в плеере: кнопка, автоперевод, живой баланс громкости
- вход по QR-коду (телефон → Яндекс → токен сам прилетает на ТВ, без ввода текста)
- настройки перевода собраны в одну секцию плеера
- обновления проверяются при запуске **из этого репозитория** и ставятся в два тапа
- периодические слияния с апстримом (процедура — [SYNC.md](SYNC.md))

Всё остальное — SmartTube как есть. Донаты автору оригинала — [у yuliskov](https://github.com/yuliskov/SmartTube).

## Дисклеймеры

- Перевод использует **неофициальный API Яндекса** (`api.browser.yandex.ru`) — то же, что
  и браузерное расширение VOT. Яндекс может изменить или закрыть его в любой момент.
- Проект не связан с Яндексом и Google. Без гарантий, только для личного использования.
- Это форк для себя: баги — в [issues этого репозитория](https://github.com/Nombah501/SmartTube-VOT/issues),
  не апстриму.

## Установка

1. Скачайте APK со страницы [релизов](https://github.com/Nombah501/SmartTube-VOT/releases/latest):
   - `SmartTube_stable_universal.apk` — для любого устройства
   - `SmartTube_stable_arm64-v8a.apk` — большинство современных приставок и ТВ
   - `SmartTube_stable_armeabi-v7a.apk` — старые боксы
2. Разрешите установку из неизвестных источников для вашего файлового менеджера
3. Установите. Обновления дальше будут приходить в само приложение

## Как пользоваться переводом

1. В плеере нажмите OK — в ряду кнопок появится иконка **«Закадровый перевод»**
   (если её нет: Настройки → Плеер → Настроить кнопки плеера → Закадровый перевод)
2. Тап по кнопке: перевод готовится (Яндекс отвечает «перевод через N мин» на свежие
   видео) и включается. Долгий тап — баланс громкости оригинал/перевод, автоперевод
3. **Автоперевод** (Настройки → Плеер → Закадровый перевод) включает перевод сам для
   видео с нерусской дорожкой

### Вход по QR (для «живых голосов»)

Живые голоса Яндекса требуют OAuth-токен. Вводить его вручную не нужно:

1. Настройки → Плеер → Закадровый перевод (Яндекс) → **Войти по QR-коду**
2. Отсканируйте QR камерой телефона (или откройте ссылку и введите код)
3. На телефоне: «Войти через Яндекс» → скопируйте токен → «Вставить и отправить»
4. Токен сохранится на приставке автоматически

Механика: короткая страница-релей (`v.n501.site`, код в `relay/`) передаёт токен
с телефона на ТВ по одноразовому 6-символьному коду. Токен живёт в релее ≤10 минут
и удаляется после первой отправки. Логин происходит в настоящем браузере телефона.

## Обновления

При каждом запуске приложение проверяет [update.json](update.json) этого репозитория
и предлагает установку, если вышел новый релиз. Источник обновлений — **этот форк**,
не апстрим. Синхронизация с апстримом — [SYNC.md](SYNC.md).

## Совместимость

Android TV / боксы / ТВ-стик с Android 5.0+ (minSdk 21 в stable-сборке).
Amazon Fire TV требует дополнительных действий — см. FAQ апстрима.

## Сборка из исходников

```bash
# JDK 17 (другие версии ломают Gradle 7.5)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=~/android-sdk   # platform android-34, build-tools 30.0.3

git clone --recurse-submodules https://github.com/Nombah501/SmartTube-VOT.git
cd SmartTube-VOT
./gradlew assembleStstableRelease
# → smarttubetv/build/outputs/apk/ststable/release/
```

Релизы подписываются личным ключом (`keystore.properties`, в репо отсутствует).

## FAQ

- **Перевод не включается на свежем видео** — Яндексу нужно время на генерацию
  дорожки (до минуты), кнопка покажет «Перевод через N мин»
- **«Нужна авторизация Яндекса»** — живые голоса требуют токена: вход по QR (см. выше)
- **Русское видео переводится?** — нет, перевод пропускает русские дорожки
- **Не обновляется по воздуху** — проверьте, что установлен APK именно из этого
  репозитория; обновления ставятся только при совпадении подписи
- **Пропал звук оригинала после выключения перевода** — должно восстанавливаться
  само; если нет, перезапустите видео и сообщите в issues

## Credits

- [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube) — базовый плеер
- [mikhkz/SmartTube](https://github.com/mikhkz/SmartTube) — интеграция VOT ([PR #5817](https://github.com/yuliskov/SmartTube/pull/5817))
- [ilyhalight/voice-over-translation](https://github.com/ilyhalight/voice-over-translation),
  [FOSWLY/vot-cli](https://github.com/FOSWLY/vot-cli),
  [FOSWLY/vot.js](https://github.com/FOSWLY/vot.js) — протокол и референс
- [AlexxIT/YandexStation](https://github.com/AlexxIT/YandexStation) — исследование авторизации

Подробнее: [CREDITS.md](CREDITS.md)

## Лицензия

MIT, унаследована от апстрима. ПО предоставляется «как есть», без каких-либо гарантий.
