# Публикация Trace в Google Play

Что здесь лежит:

- `listing-en-US.md`, `listing-ru-RU.md` — название, описания, «Что нового».
- `graphics/` — значок 512×512 и баннер 1024×500 (английский и русский).
- `screenshots/` — скриншоты 1080×1920 на двух языках (`en-*`, `ru-*`). Снять заново: Actions →
  Trace → Run workflow.
- `sample-timeline.json` — выдуманная поездка для проверяющих Google.
- Политика конфиденциальности — [`../PRIVACY.md`](../PRIVACY.md). Ссылка для Play заработает после
  слияния в `main`:
  `https://github.com/Han-Soda/google-timeline-visualizer/blob/main/trace/PRIVACY.md`

## 1. Ключ загрузки (один раз)

GitHub → Settings → Secrets and variables → Actions → New repository secret. Два секрета из
файла `trace-upload-key.txt`: `TRACE_UPLOAD_KEY` и `TRACE_UPLOAD_PASSWORD`.

Сам `trace-upload.p12` и пароль сохраните в менеджере паролей. В репозиторий их не кладите.

## 2. Сборка

Каждый запуск CI собирает `trace-<коммит>.aab` в артефакте `trace-<коммит>`; номер версии растёт
сам. В Play загружается именно `.aab`.

## 3. Play Console

1. Создать приложение: Trace, язык по умолчанию English (United States), приложение, бесплатное.
2. Store listing: тексты и картинки из этой папки; русский добавить как перевод ru-RU.
3. Категория: Travel & Local. Почта для связи — ваша.
4. Подпись: Play App Signing (по умолчанию), ключ загрузки — из CI.

## 4. Анкеты (Policy → App content)

| Раздел | Ответ |
| --- | --- |
| Privacy policy | ссылка выше |
| Ads | нет рекламы |
| App access | часть функций ограничена, инструкция ниже |
| Content rating | категория «All other app types», на все вопросы «No» |
| Target audience | 18 and over |
| News, government, financial, health | нет |
| Advertising ID | не используется |
| Data safety | ниже |
| Photo and video permissions | текст ниже |

**App access**, инструкция для проверяющих:

```
No login is needed, but Trace needs a location history file to show anything. On the device, open https://raw.githubusercontent.com/Han-Soda/google-timeline-visualizer/main/trace/play-store/sample-timeline.json and save it. In Trace, tap "Open Timeline file" and choose it. The editor opens with a sample bike ride; "Export video" makes an MP4.
```

**Data safety:**

- Collects or shares user data: **Yes**. Encrypted in transit: **Yes**.
- Users can request deletion: **No** (у приложения нет серверов, хранить нечего).
- Location → **Precise location**: collected **and** shared, not processed ephemerally,
  required, purpose **App functionality**. Это запросы фрагментов карты (OpenFreeMap, CARTO) и
  названия мест под фото.
- Больше ничего не отмечать: Хронология, фото и видео не покидают телефон.

**Photo and video permissions** (READ_MEDIA_IMAGES, READ_MEDIA_VIDEO):

```
Trace makes a video of the user's trips from their location history and shows their photos and videos on the route where they were taken. "Find from these days" looks through the whole gallery for the photos and videos taken on the trip's dates and suggests them; the system photo picker can't search the gallery by date. Access is asked for only when the user taps that button. Photos can also be added one at a time with the photo picker, without the permission.
```

## 5. Тест перед выпуском

Для нового личного аккаунта: закрытый тест (Closed testing) минимум с 12 тестировщиками, 14 дней
подряд. После этого — Apply for production.

## 6. Если отклонят доступ к фото

Убрать READ_MEDIA_IMAGES/READ_MEDIA_VIDEO и кнопку «Найти за эти дни»; фото по-прежнему
добавляются через системный выбор. Это небольшая правка.
