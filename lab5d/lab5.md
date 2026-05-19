Набор endpoint’ов

GET    /api/v1/cities
GET    /api/v1/airports
GET    /api/v1/cities/{city}/airports
GET    /api/v1/airports/{airport_code}/inbound-schedule
GET    /api/v1/airports/{airport_code}/outbound-schedule
GET    /api/v1/routes
POST   /api/v1/bookings
POST   /api/v1/check-ins


1) Получение списка городов

Для получения городов используется endpoint GET /api/v1/cities.

Он нужен для того, чтобы клиент мог сначала выбрать город отправления или прибытия. Запрос поддерживает параметр role, который показывает, какие города интересуют клиента:
только города отправления (source), только города назначения (destination) или оба варианта (both).

Пример запроса:

GET /api/v1/cities?role=both

Пример ответа:

{
  "role": "both",
  "items": [
    { "name": "Ahmedabad" },
    { "name": "Atlanta" },
    { "name": "Mumbai" }
  ]
}

2) Получение списка аэропортов

Для этого используется GET /api/v1/airports.

Логика похожа на города: клиент может запросить аэропорты отправления, прибытия или все доступные аэропорты сразу. В ответе сервер возвращает не только код аэропорта, но и его название, город, страну и часовой пояс.

Пример запроса:

GET /api/v1/airports?role=source

Пример ответа:

{
  "role": "source",
  "items": [
    {
      "airport_code": "AMD",
      "airport_name": "Sardar Vallabhbhai Patel",
      "city": "Ahmedabad",
      "country": "India",
      "timezone": "Asia/Kolkata"
    }
  ]
}


3) Получение аэропортов конкретного города

Для этого используется GET /api/v1/cities/{city}/airports.

Этот endpoint нужен потому, что один и тот же город может включать несколько аэропортов. Если пользователь выбрал город, система должна уметь показать, какие конкретно аэропорты к нему относятся.

Пример:

GET /api/v1/cities/Moscow/airports

Ответ содержит список аэропортов внутри города.

Если город найден, сервер возвращает 200 OK.
Если такого города нет, сервер возвращает 404 Not Found.

4) Расписание прилётов

Для просмотра прилётов используется GET /api/v1/airports/{airport_code}/inbound-schedule.

В ответе возвращаются:
номер рейса, дни недели, локальное время прилёта, код аэропорта отправления и город отправления.

Пример ответа:

{
  "airport_code": "LED",
  "items": [
    {
      "flight_no": "PG0123",
      "days_of_week": [1, 3, 5],
      "time_local": "14:25:00",
      "origin_airport": "SVO",
      "origin_city": "Moscow"
    }
  ]
}

Если расписание найдено, код ответа — 200 OK.
Если аэропорт не найден или для него нет данных, возвращается 404 Not Found.

5) Расписание вылетов

Для просмотра вылетов используется GET /api/v1/airports/{airport_code}/outbound-schedule.

Здесь в ответе возвращаются:
номер рейса, дни недели, локальное время вылета, код аэропорта назначения и город назначения.

Пример ответа:

{
  "airport_code": "SVO",
  "items": [
    {
      "flight_no": "PG0653",
      "days_of_week": [1, 2, 3, 4, 5],
      "time_local": "01:05:00",
      "destination_airport": "HGH",
      "destination_city": "Hangzhou"
    }
  ]
}

Коды ответа такие же:
200 OK при успешном выполнении и 404 Not Found, если аэропорт не найден.

6) Поиск маршрутов

Самым важным endpoint’ом сервиса является GET /api/v1/routes.

Он используется для поиска маршрутов между двумя точками. Точка может быть задана как аэропорт или как город. Если задан город, сервис должен автоматически учитывать все аэропорты этого города.

Параметры запроса:

origin_kind — тип начальной точки: airport или city
origin — значение начальной точки
destination_kind — тип конечной точки
destination — значение конечной точки
departure_date — дата вылета
booking_class — класс бронирования: Economy, Comfort, Business
max_connections — число пересадок: 0, 1, 2, 3, unbound
limit — число результатов

Пример реального запроса, который использовался при тестировании:

GET /api/v1/routes?origin_kind=airport&origin=AMD&destination_kind=airport&destination=BOM&departure_date=2025-12-01&booking_class=Economy&max_connections=0

Пример ответа:

{
  "query": {
    "origin_kind": "airport",
    "origin": "AMD",
    "destination_kind": "airport",
    "destination": "BOM",
    "departure_date": "2025-12-01",
    "booking_class": "Economy",
    "max_connections": "0",
    "limit": 50
  },
  "items": [
    {
      "connections": 0,
      "flight_ids": [11066],
      "flight_nos": ["PG0055"],
      "origin_airport": "AMD",
      "origin_city": "Ahmedabad",
      "destination_airport": "BOM",
      "destination_city": "Mumbai",
      "first_departure": "2025-12-01T09:15:00+07:00",
      "final_arrival": "2025-12-01T10:20:00+07:00",
      "route_free_seats": 56,
      "segments": [
        {
          "flight_id": 11066,
          "flight_no": "PG0055",
          "departure_airport": "AMD",
          "departure_city": "Ahmedabad",
          "arrival_airport": "BOM",
          "arrival_city": "Mumbai",
          "scheduled_departure": "2025-12-01T09:15:00+07:00",
          "scheduled_arrival": "2025-12-01T10:20:00+07:00",
          "free_seats_in_class": 56
        }
      ]
    }
  ]
}


Если маршрут найден, сервер возвращает 200 OK.
Если точки поиска не существуют, возвращается 404 Not Found.
Если параметры переданы неправильно, возвращается 422 Unprocessable Entity.

7) Создание бронирования

Для создания бронирования используется POST /api/v1/bookings.

Этот endpoint принимает JSON с данными пассажира и списком выбранных рейсов. В текущей реализации он рассчитан на одного пассажира.

Пример тела запроса:

{
  "flight_ids": [11066],
  "fare_conditions": "Economy",
  "passenger_id": "P1234567",
  "passenger_name": "Lilianna Test"
}

Пример ответа:

{
  "book_ref": "35DIGE",
  "ticket_no": "6161195395371",
  "passenger_id": "P1234567",
  "passenger_name": "Lilianna Test",
  "fare_conditions": "Economy",
  "total_amount": "3250.00",
  "flight_ids": [11066]
}

Смысл работы такой:
сервис получает выбранные рейсы, проверяет наличие мест, рассчитывает стоимость, создаёт бронь, создаёт билет и связывает билет с рейсом.

При успехе возвращается 201 Created.
Если рейс не найден — 404 Not Found.
Если мест уже нет — 409 Conflict.
Если структура маршрута некорректна — 422 Unprocessable Entity.

8) Online check-in

Для регистрации пассажира на рейс используется POST /api/v1/check-ins.

На входе нужно передать номер билета и идентификатор рейса. При необходимости можно также передать предпочтительное место, но в базовом варианте сервис сам выбирает первое доступное место.

Пример запроса:

{
  "ticket_no": "6161195395371",
  "flight_id": 11066
}

Пример ответа:

{
  "ticket_no": "6161195395371",
  "flight_id": 11066,
  "seat_no": "10A",
  "boarding_no": 1,
  "boarding_time": "2025-12-01T07:00:00+07:00"
}

Смысл этой операции в том, что система должна:
проверить билет, проверить принадлежность билета рейсу, проверить статус рейса, проверить окно регистрации и затем назначить место.

При успешной регистрации возвращается 201 Created.

Если билет или рейс не найдены, возвращается 404 Not Found.
Если пассажир уже зарегистрирован или место уже занято — 409 Conflict.
Если регистрация ещё не открыта или уже закрыта — 422 Unprocessable Entity.

9) HTTP-коды, используемые в сервисе

В проектируемом API предусмотрены следующие основные коды ответа:

200 OK — запрос успешно выполнен и данные возвращены.

201 Created — создан новый объект, например бронь или регистрация на рейс.

404 Not Found — запрошенный объект не найден. Это может быть город, аэропорт, билет, рейс или точка маршрута.

409 Conflict — конфликт состояния. Например, когда свободных мест уже нет или check-in уже выполнен.

422 Unprocessable Entity — запрос синтаксически понятен, но нарушает бизнес-логику, например check-in ещё не открыт.

500 Internal Server Error — внутренняя ошибка сервера. Этот код не является штатным бизнес-результатом, но должен учитываться как технически возможный.

10) Предполагаемая структура реализации

main.py — описание endpoint-ов и запуск сервера
models.py — схемы запросов и ответов
repository.py — основная логика и SQL
db.py — подключение к PostgreSQL
config.py — настройки приложения
errors.py — собственные типы ошибок
sql/indexes.sql — индексы для ускорения запросов
