# Парсер отзывов JetBrains Marketplace

Сервис принимает ссылку на плагин JetBrains Marketplace, загружает первую страницу отзывов через WebClient и разбирает HTML из поля `comment` с помощью Jsoup. Отзывы хранятся в памяти до остановки приложения.

## Запуск

Установите JDK 17 и откройте терминал в корне проекта. Gradle Wrapper сам скачает Gradle при первом запуске.

### Windows

```bat
.\gradlew.bat bootRun
```

### macOS и Linux

```sh
sh ./gradlew bootRun
```

Сервер доступен на `http://localhost:8080`. Для загрузки отзывов нужен доступ к `plugins.jetbrains.com`. API-ключ и аккаунт не требуются.

## Проверка

1. Отправьте **POST** на `http://localhost:8080/post` с заголовком `Content-Type: application/json`.

   ```json
   {"url":"https://plugins.jetbrains.com/plugin/164-ideavim"}
   ```

   Поддерживается и ссылка на вкладку [отзывов IdeaVim](https://plugins.jetbrains.com/plugin/164-ideavim/reviews). При успешном сборе сервер возвращает `200 OK` и `{"pluginId":164}`.

2. Выполните **GET** по адресу `http://localhost:8080/answer?pluginId=164`. В ответе будет статус `SUCCEEDED` и массив отзывов. Пример ответа с тестовыми данными.

   ```json
   {
     "pluginId": 164,
     "status": "SUCCEEDED",
     "reviews": [
       {
         "id": 42,
         "text": "Works well & fast.",
         "rating": 4,
         "author": "Demo User",
         "publishedAt": "2026-10-29T18:00:10Z"
       }
     ],
     "error": null
   }
   ```

Сервис запрашивает до 20 записей за сбор и сохраняет отзывы, в которых есть текст и оценка от 1 до 5. Повторный сбор обновляет совпадающие отзывы по ID. До первого сбора статус `IDLE`. Некорректная ссылка дает `400`, ошибка Marketplace - `502`, тайм-аут - `504`. После ошибки `GET /answer` показывает `FAILED` и сохраняет ранее загруженные отзывы.

Сервер возвращает ошибки в формате `application/problem+json` и указывает причину в поле `detail`. Ниже приведен ответ на `GET /answer?pluginId=0`.

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "pluginId must be positive",
  "instance": "/answer"
}
```

### Bruno

Тот же сценарий можно проверить через готовую коллекцию в [requests](requests).

1. [Скачайте Bruno](https://www.usebruno.com/downloads), установите его и откройте папку `requests` как коллекцию.
2. Выберите окружение **Local**.
3. Откройте **Fetch plugin reviews** (POST `/post`) и нажмите **Send**. Запрос собирает отзывы IdeaVim и сохраняет полученный ID в переменную `PLUGIN_ID`. Для другого плагина поменяйте `PLUGIN_URL` в этом запросе.
4. Отправьте **Get reviews from fetched plugin** (GET `/answer`). Bruno подставит сохраненный `PLUGIN_ID`. В ответе будут статус `SUCCEEDED` и массив отзывов.
5. Для проверки ошибок отправьте **Reject unsupported plugin URL** (POST `/post`) и **Reject invalid plugin ID** (GET `/answer`). Оба запроса проверяют код `400` и причину ошибки в поле `detail`.

## Тесты

### Windows

```bat
.\gradlew.bat test
```

### macOS и Linux

```sh
sh ./gradlew test
```

Тесты проверяют парсинг на локальных фикстурах и используют локальный HTTP-сервер вместо Marketplace.
