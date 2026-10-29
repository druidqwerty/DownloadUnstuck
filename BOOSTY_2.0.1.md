# Пост для Boosty: Download Unstuck 2.0.1

Мод вышел 29.10.2026 в Мастерской Steam (3829163351, публичный) и на GitHub (релиз v2.0.1); ссылки настоящие.
Публикация: C:\Users\druid\.openclaw\workspace\tools\boosty\README.md. По правилу Boosty: сначала английский, потом русский.

Title / Заголовок: Download Unstuck 2.0.1 — hosting no longer hangs on Workshop downloads / свой сервер больше не виснет на загрузке модов

---

Update for Project Zomboid (B42.21+): Host Unstuck is now **Download Unstuck 2.0.1**, tested in a real hosting session: everything works.

When you host a game from the main menu with a big mod list, the server first downloads every Workshop item one at a time, and sometimes it simply stops: the status stays on "Downloaded 259 of 308" forever. The only cure was to press Stop, then Start.

**Download Unstuck does that for you:**

- watches the "Downloaded X of Y" counter; when it stops moving for 40 s, it presses Stop and Start itself and the download goes on from the same item;
- gives big items extra time and waits longer after each restart, so a slow but working download is not cut off; after 8 restarts it gives up and names the stuck item;
- shows the status in your language instead of garbled server text, the item being downloaded, and an honest progress bar.

**With ZombieBuddy (optional):** the server you host requests up to 4 items from Steam at once instead of strictly one after another, requests a stuck item again before restarting anything, and is restarted at once if it freezes.

Client-side: enable it in the main-menu mod list and restart the game once; the server's mod list and the other players are not involved. Settings: Options > Mods > Download Unstuck.

**Had Host Unstuck?** Nothing to do: the mod id, jar and settings stay HostUnstuck, so nothing has to be re-enabled or re-approved.

- Steam Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
- GitHub (source and zip): https://github.com/druidqwerty/DownloadUnstuck

The code is written by an AI; the direction, the testing and the time are mine. Thanks to everyone who supports this!

---

Обновление для Project Zomboid (B42.21+): Host Unstuck теперь называется **Download Unstuck 2.0.1** и проверен в настоящей игре по сети: всё работает.

Если запускать свой сервер из меню с большим модпаком, сервер сначала качает моды мастерской строго по одному и иногда намертво встаёт: статус «Загружено 259 из 308» не меняется никогда. Помогало только нажать «Стоп», потом «Старт».

**Download Unstuck делает это сам:**

- следит за счётчиком «Загружено X из Y»; если он стоит 40 с, сам нажимает «Стоп» и «Старт», и загрузка продолжается с того же мода;
- большим модам даёт больше времени и после каждого перезапуска ждёт дольше, чтобы не оборвать медленную, но живую загрузку; после 8 перезапусков сдаётся и называет мод, на котором застряло;
- показывает статус на вашем языке вместо кракозябр сервера, какой мод сейчас качается, и честный прогресс-бар.

**С ZombieBuddy (по желанию):** ваш сервер просит у Steam до 4 модов сразу, а не строго по одному, застрявший мод сначала запрашивает заново, а зависший сервер перезапускает сразу.

Мод клиентский: включите его в списке модов главного меню и один раз перезапустите игру; список модов сервера и другие игроки не затрагиваются. Настройки: Настройки > Моды > Download Unstuck.

**Стоял Host Unstuck?** Ничего делать не нужно: id мода, jar и файл настроек остались HostUnstuck, включать и подтверждать заново не придётся.

- Steam Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
- GitHub (исходники и архив): https://github.com/druidqwerty/DownloadUnstuck

Код пишет нейросеть, а с меня — постановка задач, тесты и время. Спасибо всем, кто поддерживает!
