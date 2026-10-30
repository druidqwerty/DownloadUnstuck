Title / Заголовок: Download Unstuck 2.1.1 — Workshop progress error fix / исправление ошибок загрузки мастерской

---

**Download Unstuck 2.1.1** is out as a test version.

This fixes the repeated `__div not defined for operands` Lua error that appeared once per downloaded Workshop item while hosting. The server now sends the vanilla item count before progress, and the client can recover it from every `X of Y` line.

Java SimTest, server-style SelfTest including nested player-save classes, and all 16 Lua scenarios pass. A clean 2.1.1 client start with ZombieBuddy is confirmed. A full hosting session on this version has not been completed yet — please test and send `Zomboid/console.txt` and `coop-console.txt` if something goes wrong.

- Steam Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
- GitHub (source and ZIP): https://github.com/druidqwerty/DownloadUnstuck/releases/tag/v2.1.1

---

Вышла тестовая версия **Download Unstuck 2.1.1**.

Исправлена повторяющаяся ошибка `__div not defined for operands`, которая появлялась на каждой скачанной позиции мастерской при запуске своего сервера. Сервер снова отправляет начальное число элементов, а клиент при необходимости восстанавливает его из каждой строки «X из Y».

Java SimTest, серверный SelfTest с вложенными классами сохранения игрока и все 16 Lua-сценариев пройдены. Чистый запуск клиента 2.1.1 с ZombieBuddy подтверждён. Полная сессия хостинга на этой версии ещё не завершена — тестируйте и присылайте `Zomboid/console.txt` и `coop-console.txt` при проблемах.

- Мастерская Steam: https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
- GitHub (исходники и ZIP): https://github.com/druidqwerty/DownloadUnstuck/releases/tag/v2.1.1
