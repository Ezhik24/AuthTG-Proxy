# AuthTG-Proxy

Связка AuthTG с Velocity: блокирует неавторизованных игроков на сервере авторизации и безопасно маршрутизирует команды `/login` и `/register`.

## Требования

- Velocity 3.2+
- Paper 1.19+ с основным плагином AuthTG
- Java 17 для Paper; версия Java для Velocity должна соответствовать требованиям вашего ядра

## Установка

1. Скопируйте `AuthTGVelocity-1.1.jar` в `plugins/` Velocity.
2. Скопируйте `AuthTG-3.1.jar` в `plugins/` Paper-сервера авторизации.
3. В `velocity.toml` добавьте backend и modern forwarding:

```toml
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:25566"
try = ["lobby"]
```

4. В `config/paper-global.yml` соответствующей версии Paper включите Velocity и укажите тот же secret. Backend не должен быть напрямую доступен из интернета.
5. Запустите Velocity один раз и настройте `plugins/authtgvelocity/config.properties`:

```properties
hub-server=lobby
authorization-timeout-seconds=600
```

6. В `plugins/AuthTG/config.yml` на lobby включите:

```yaml
velocity:
  enabled: true
```

7. Полностью перезапустите Velocity и Paper. Убедитесь, что в консоли есть `AuthTG authorization server: lobby`.

## Команды

- `/authtgvelocity status` — показывает auth-сервер, канал, TTL и число активных авторизаций.
- `/authtgvelocity reload` — перечитывает `config.properties` и `messages.properties` без перезапуска.
- Право для обеих команд: `authtgvelocity.admin`.

## Безопасность

Plugin-message `authtg:main` принимается только от backend, имя которого совпадает с `hub-server`, и только для UUID игрока соответствующего соединения. Пакеты от игроков и других backend-серверов отклоняются. Сессии автоматически удаляются по TTL и при отключении игрока.

Команды с паролями скрываются фильтрами логов Velocity и Paper. Не устанавливайте плагины, которые отдельно журналируют полный текст команд авторизации.

## Проверка

```bash
mvn -f AuthTGVelocity/pom.xml test
```

Тесты покрывают истечение сессии, успешное завершение авторизации, отклонение сообщений с другого backend и запрет перехода с lobby на игровой сервер до авторизации.

## Premium-вход

Установите FastLoginPlus одновременно на Velocity и lobby и выполните его настройку proxy ID. В конфигурации FastLoginPlus используйте `premium-warning: true`, `autoLogin: true` и `premiumUuid: false`, чтобы сохранить UUID уже зарегистрированных AuthTG-аккаунтов.

После обычного входа игрок дважды выполняет `/premium`. Первый вызов показывает предупреждение, второй включает Mojang-проверку и отключает игрока. На следующем подключении подтверждённый premium-игрок автоматически входит в AuthTG; настроенная 2FA при этом сохраняется.

## Капча

В `plugins/AuthTG/config.yml` выберите режим:

```yaml
captcha:
  enabled: true
  type: "TEXT_MAP" # TEXT_MAP или WOOL
```

`TEXT_MAP` выдаёт игроку карту с шестизначным кодом; ответ вводится командой `/captcha <код>`. `WOOL` использует прежний инвентарь с цветной шерстью.

## Диагностика

- `hub-server [не найден]`: имя не совпадает с ключом в `[servers]` файла `velocity.toml`.
- Команды не доходят до lobby: проверьте `/authtgvelocity status`, наличие AuthTG на lobby и `velocity.enabled: true`.
- После входа игрок остаётся заблокирован: проверьте доставку `auth_success` и одинаковый UUID при modern forwarding.
