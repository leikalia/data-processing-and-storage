
# lab1 — RSA Key Server/Client

## Структура
```
lab1/
 └── src/
     ├── main/java/org/example/lab1/
     │    ├── KeyServer.java
     │    └── KeyClient.java
     └── test/java/org/example/lab1/
          ├── KeyServerIntegrationTest.java
          └── TestUtils.java
build.gradle
settings.gradle
```

## Быстрый старт (IntelliJ IDEA)
1. **Open** → выбери папку `lab1`.
2. Дождись импорта Gradle.
3. Сгенерируй ключ CA (или дай свой `.pem`). Для тестов можно ничего не делать — тест сам генерирует временный ключ.

### Запуск сервера
Run → Edit Configurations… → + **Application**  
- Name: `KeyServer`
- Main class: `org.example.lab1.KeyServer`
- Program arguments:
```
5555 4 ca-key.pem CN=MyCA
```
(если `ca-key.pem` в корне проекта)

### Запуск клиента
Создай конфигурацию **Application**:
- Name: `KeyClient`
- Main class: `org.example.lab1.KeyClient`
- Program arguments:
```
localhost 5555 Alice alice_out
```

### Тесты
Запусти тест **KeyServerIntegrationTest**.  
Он:
- генерирует временный ключ CA (PKCS#8 PEM);
- поднимает сервер в отдельном процессе с `-Drsa.keySize=1024` (быстро);
- запускает **2 клиента** с одинаковым именем `Bob` и разными префиксами;
- сравнивает: файлы `.key` и `.crt` должны быть **идентичны**.

Запуск тестов через Gradle:
```
./gradlew test -Drsa.keySize=1024
```

## Консоль (альтернатива IDEA)
Сгенерируй ключ CA (пример):
```
# Быстро и просто — можно 1024 для демо
# (или используй свой готовый PEM)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out ca-key.pem
```
Сервер:
```
./gradlew runServer --args "5555 4 ca-key.pem CN=MyCA"
```
Клиент:
```
./gradlew runClient --args "localhost 5555 Alice alice_out"
```

> Совет: для больших ключей (8192) это долго. В учебных тестах используй `-Drsa.keySize=1024`.
