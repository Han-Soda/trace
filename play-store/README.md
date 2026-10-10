# Как выложить Trace в Google Play

Всё для магазина уже лежит в этой папке. Ниже шаги по порядку; в скобках — как пункт называется
в английском интерфейсе Play Console.

| Файл | Что это |
| --- | --- |
| `listing-en-US.md`, `listing-ru-RU.md` | название, описания и «Что нового» — копировать как есть |
| `graphics/icon-512.png` | значок 512×512 |
| `graphics/feature-graphic-en.png`, `-ru.png` | баннер 1024×500 |
| `screenshots/en-*.png`, `ru-*.png` | скриншоты 1080×1920 |
| `sample-timeline.json` | выдуманная поездка для проверяющих Google |
| [`../PRIVACY.md`](../PRIVACY.md) | политика конфиденциальности |

## 1. Один раз в GitHub

1. **Ключ.** Settings → Secrets and variables → Actions → New repository secret. Добавьте два
   секрета из файла `trace-upload-key.txt`: `TRACE_UPLOAD_KEY` и `TRACE_UPLOAD_PASSWORD`.
   Сам файл `trace-upload.p12` и пароль храните у себя; в репозиторий их не кладите.
2. **Включить Issues.** Settings → General → Features → Issues. На них ссылается политика.

## 2. Собрать файл для Play

Actions → Trace → Run workflow → Run workflow. Через 10–15 минут откройте запуск: вверху будет
«Signed with the upload key» и ссылка Download `trace-…`, в архиве — файл `.aab`. Его и загружать в
Play. Номер версии растёт сам. Если секретов нет или ключ вставлен не целиком, сборка остановится с
красной ошибкой. Скриншоты переснимаются, только если отметить *Also retake the store screenshots*.

## 3. Аккаунт разработчика

[play.google.com/console](https://play.google.com/console) → регистрация как частное лицо
(Personal), взнос 25 $. Проверка личности: документ с фото и отдельный документ с адресом (счёт
или банковская выписка не старше 60 дней; имя и адрес как в профиле). Подтверждение телефоном
Android: настоящий телефон без root, Android 10 или новее, приложение Play Console, тот же
аккаунт. Google может проверять несколько дней.

## 4. Создать приложение

Create app (Создать приложение):

- App name: `Trace: Travel Map Videos`
- Package name: `io.github.hansoda.trace` — как в приложении, потом не меняется. Если консоль
  скажет, что пакет уже встречался на устройствах, и попросит подтвердить ключ (Add key), нужен
  APK, подписанный этим ключом
- Default language: English (United States) – en-US
- App or game: App · Free or paid: Free
- отметить согласия с правилами

## 5. Policy → App content (Контент приложения)

Проще всего с Dashboard: блок «Set up your app» ведёт к каждому пункту.

| Раздел | Что выбрать |
| --- | --- |
| Privacy policy | `https://github.com/Han-Soda/google-timeline-visualizer/blob/main/trace/PRIVACY.md` |
| App access | All or some functionality is restricted → инструкция ниже |
| Ads | No, my app does not contain ads |
| Content rating | почта, категория «прочие» (All Other App Types или Utility, Productivity, Communication, or Other), на все вопросы No |
| Target audience | только 18 and over |
| News app | No |
| Government apps, Financial features, Health | No / ничего не отмечать |
| Advertising ID | No |
| Data safety | ниже |
| Photo and video permissions | текст ниже |
| EU trader status | если спросят: не зарабатываешь на приложении — обычно «не трейдер»; трейдеру Google показывает адрес, телефон и почту |

**App access** — Add instructions, имя любое, текст:

```
No login is needed, but Trace needs a location history file to show anything. On the device, open https://raw.githubusercontent.com/Han-Soda/google-timeline-visualizer/main/trace/play-store/sample-timeline.json and save it. In Trace, tap "Open Timeline file" and choose it. The editor opens with a sample bike ride; "Export video" makes an MP4.
```

**Data safety:**

1. Does your app collect or share any of the required user data types? **Yes**
2. Is all of the user data collected by your app encrypted in transit? **Yes**
3. Account creation: **My app does not allow users to create an account**
4. Do you provide a way for users to request that their data is deleted? **No**
5. Типы данных: отметить только **Location → Precise location**
6. Precise location: Collected **и** Shared; processed ephemerally — **No**; required — **Users
   can't turn off this data collection**; purpose — **App functionality** (в обоих местах).

Это запросы фрагментов карты и названий мест под фото. Хронология, фото и видео не покидают
телефон, их не отмечать.

**Photo and video permissions** — Describe your app's use:

```
Trace makes a video of the user's trips from their location history and shows their photos and videos on the route where they were taken. "Find from these days" looks through the whole gallery for the photos and videos taken on the trip's dates and suggests them; the system photo picker can't search the gallery by date. Access is asked for only when the user taps that button. Photos can also be added one at a time with the photo picker, without the permission.
```

## 6. Страница в магазине

Grow users → Store presence → Main store listing (Основная страница):

- App name, Short description, Full description — из `listing-en-US.md`
- App icon — `graphics/icon-512.png`; Feature graphic — `graphics/feature-graphic-en.png`
- Phone screenshots — `screenshots/en-1…en-8` (можно все 8)
- Add translations → Russian (ru-RU) — тексты из `listing-ru-RU.md`, баннер `-ru.png`,
  скриншоты `ru-*`

Store settings (Настройки магазина): категория **Travel & Local**, ваша почта.

## 7. Первый выпуск: внутренний тест

Test and release → Testing → Internal testing → Create new release (если кнопка серая, не все
задачи на главной выполнены). Если покажут экран про Play App Signing — Continue. App bundles:
загрузить `.aab`. Release name заполнится само, оставить. Release notes из `listing-*.md`. Next →
Save → опубликовать. Такой тест 12 тестировщиков не засчитывает.

## 8. Закрытый тест

Test and release → Testing → Closed testing → Create track:

1. Testers: список из **12+ адресов Gmail** (друзья, родные). Сохранить.
2. Create new release → тот же `.aab` (загрузить заново или Add from library) → Release notes →
   Next → Save.
3. Policy → App content → **Photo and video permissions** — появится после первой загрузки
   `.aab`, текст выше.
4. Publishing overview → **Send changes for review**.
5. Разослать тестировщикам ссылку «Join on the web». Каждый должен нажать «Стать
   тестировщиком» и установить приложение.
6. Ждать **14 дней подряд**, пока все 12 остаются в тесте. Если кто-то выйдет раньше, отсчёт
   может начаться заново.

## 9. Выпуск

Dashboard → **Apply for production**: короткие ответы, как прошёл тест (сколько людей, какие
отзывы, что поменяли). После одобрения: Production → Create new release → тот же или новый `.aab`.

## Когда добавите рекламу

Реклама внутри приложения — отдельный выпуск. До него: App content → Ads → Yes, обновить Data
safety и Advertising ID, поправить политику конфиденциальности (сейчас в ней написано, что
рекламы нет) и пересмотреть статус трейдера. Если реклама есть, а в консоли указано «нет», Google
может приостановить приложение.

## Если Google что-то отклонит

Пришлите мне текст письма. Если откажут в доступе к фото, уберу кнопку «Найти за эти дни» и
разрешение — фото останутся через системный выбор.
