# Парсер отзывов JetBrains Marketplace

Сервис принимает ссылку на плагин JetBrains Marketplace и ставит задание в очередь. Планировщик раз в секунду передает задания в пул из двух потоков. При сборе сервис загружает первую страницу отзывов через WebClient и разбирает HTML из поля `comment` с помощью Jsoup. Отзывы сохраняются в JSON-файлах, по одному на плагин.

## Запуск

Для запуска нужен JDK 17. Команды выполняются из корня проекта.

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

1. Отправить **POST** на `http://localhost:8080/post` с заголовком `Content-Type: application/json`.

   ```json
   {"url":"https://plugins.jetbrains.com/plugin/164-ideavim"}
   ```

   Подходит и ссылка на вкладку [отзывов IdeaVim](https://plugins.jetbrains.com/plugin/164-ideavim/reviews). Когда сервер принимает задание, он возвращает `202 Accepted` и `{"pluginId":164}`.

2. Выполнить **GET**, подставив `pluginId` из ответа на POST. Для IdeaVim адрес будет `http://localhost:8080/answer?pluginId=164`. Если статус `QUEUED` или `RUNNING`, запрос нужно повторить. При `SUCCEEDED` ответ содержит собранные отзывы, при `FAILED` поле `error` содержит причину. Ниже пример успешного ответа с тестовыми данными.

   ```json
   {
     "pluginId": 164,
     "status": "SUCCEEDED",
     "requestedAt": "2026-11-08T12:00:00Z",
     "completedAt": "2026-11-08T12:00:01Z",
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

Сервис запрашивает до 20 записей за сбор и сохраняет отзывы, в которых есть текст и оценка от 1 до 5. До первого сбора статус `IDLE`. Повторный POST для плагина со статусом `QUEUED` или `RUNNING` возвращает `202` для уже принятого задания. После завершения новый POST запускает обновление отзывов по ID.

Очередь вмещает 16 ожидающих заданий. Если она заполнена, POST для нового задания вернет `503`. Некорректная ссылка дает `400`. При ошибке Marketplace или таймауте GET показывает `FAILED` с причиной и возвращает ранее загруженные отзывы.

Сервер возвращает ошибки в формате `application/problem+json` и указывает причину в поле `detail`. Ниже приведен ответ на `GET /answer?pluginId=0`.

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "pluginId must be positive",
  "instance": "/answer"
}
```

### Проверка после перезапуска

1. После статуса `SUCCEEDED` сохранить массив `reviews` из ответа GET и проверить, что появился файл `data/reviews/164.json`. В журнале приложения поток `review-log` пишет о завершении сбора или об ошибке.
2. Перезапустить приложение и выполнить `GET http://localhost:8080/answer?pluginId=164` без повторного POST. Сравнить массив `reviews` целиком с сохраненным до перезапуска. В новом ответе ожидаются статус `IDLE` и `null` в полях `requestedAt` и `completedAt`. Статусы заданий хранятся в памяти.

### Bruno

Тот же сценарий можно проверить через готовую коллекцию в [requests](requests). Bruno сам повторяет GET, пока идет сбор отзывов.

1. Скачать и установить [Bruno](https://www.usebruno.com/downloads), затем открыть папку `requests` как коллекцию.
2. Выбрать окружение **Local**. В нем уже указан адрес `http://127.0.0.1:8080`. Если приложение запущено на другом порту, нужно обновить `BASE_URL` в окружении.
3. В меню коллекции **...** выбрать **Run**, отметить первые четыре запроса и нажать **Run Selected Requests**.

- **Fetch plugin reviews** (POST `/post`) ставит сбор в очередь, проверяет `202 Accepted` и сохраняет `PLUGIN_ID`. Ссылка на плагин задается переменной `PLUGIN_URL`. По умолчанию используется IdeaVim.
- **Get reviews from fetched plugin** (GET `/answer`) подставляет `PLUGIN_ID` и ждет завершения сбора до 15 секунд. При успехе проверяет статус `SUCCEEDED`, даты и поля отзывов.
- **Reject unsupported plugin URL** (POST `/post`) проверяет ответ `400` и поле `detail` для ссылки с посторонним доменом.
- **Reject invalid plugin ID** (GET `/answer`) проверяет ответ `400` и поле `detail` для `pluginId=0`.

После перезапуска отправить отдельно **Get saved reviews** (GET `/answer`). Запрос проверяет статус `IDLE`, пустые даты задания и непустой массив отзывов. Для сравнения всего массива использовать ответ **Get reviews from fetched plugin**, полученный до перезапуска. По умолчанию проверяются отзывы IdeaVim. Для другого плагина ID задается переменной `SAVED_PLUGIN_ID`.

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
