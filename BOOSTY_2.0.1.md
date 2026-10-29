# Пост для Boosty: Download Unstuck 2.0.1 (опубликован 29.10.2026 08:58: https://boosty.to/lrgpp/posts/d5bff520-d74f-402c-b702-dbfb0fd36ad6; после этого найден вылет 2.0.1 с ZombieBuddy, см. 2.0.2)

Мод вышел 29.10.2026 в Мастерской Steam (3829163351, публичный) и на GitHub (релиз v2.0.1); ссылки настоящие.
Публикация: C:\Users\druid\.openclaw\workspace\tools\boosty\README.md. По правилу Boosty: сначала английский, потом русский.

Title / Заголовок: Download Unstuck 2.0.1 — hosting no longer hangs on Workshop downloads / свой сервер больше не виснет на загрузке модов

---

**Correction, 29.10:** with ZombieBuddy, 2.0.1 shuts the hosted server down at "Downloaded 0 of N" as soon as a mod needs a download or an update: the game's Steam connection allows only one download at a time. Fixed in **Download Unstuck 2.0.2**, please update: https://boosty.to/lrgpp/posts/798ce15b-706f-4ab1-87b9-7fda71c5b9c7

Update for Project Zomboid (B42.21+): Host Unstuck is now **Download Unstuck 2.0.1**.

When you host a game from the main menu with a big mod list, the server first downloads every Workshop item one at a time, and sometimes it simply stops: the status stays on "Downloaded 259 of 308" forever. The only cure was to press Stop, then Start.

**Download Unstuck does that for you:**

- watches the "Downloaded X of Y" counter; when it stops moving for 40 s, it presses Stop and Start itself and the download goes on from the same item;
- gives big items extra time and waits longer after each restart, so a slow but working download is not cut off; after 8 restarts it gives up and names the stuck item;
- shows the status in your language instead of garbled server text, the item being downloaded, and an honest progress bar.

**With ZombieBuddy (optional):** 2.0.1 asked Steam for up to 4 items at once; that is what crashes the hosted server, so 2.0.2 downloads one item at a time again and restarts a server frozen inside Steam after 30 s.

Client-side: enable it in the main-menu mod list and restart the game once; the server's mod list and the other players are not involved. Settings: Options > Mods > Download Unstuck.

**Had Host Unstuck?** Nothing to do: the mod id, jar and settings stay HostUnstuck, so nothing has to be re-enabled or re-approved.

- Steam Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
- GitHub (source and zip): https://github.com/druidqwerty/DownloadUnstuck

The code is written by an AI; the direction, the testing and the time are mine. Thanks to everyone who supports this!

---

**Поправка от 29.10:** с ZombieBuddy версия 2.0.1 выключает свой сервер на «Загружено 0 из N», как только мод нужно скачать или обновить: игровое подключение к Steam разрешает только одну загрузку за раз. Исправлено в **Download Unstuck 2.0.2**, обновитесь: https://boosty.to/lrgpp/posts/798ce15b-706f-4ab1-87b9-7fda71c5b9c7

Обновление для Project Zomboid (B42.21+): Host Unstuck теперь называется **Download Unstuck 2.0.1**.

Если запускать свой сервер из меню с большим модпаком, сервер сначала качает моды мастерской строго по одному и иногда намертво встаёт: статус «Загружено 259 из 308» не меняется никогда. Помогало только нажать «Стоп», потом «Старт».

**Download Unstuck делает это сам:**

- следит за счётчиком «Загружено X из Y»; если он стоит 40 с, сам нажимает «Стоп» и «Старт», и загрузка продолжается с того же мода;
- большим модам даёт больше времени и после каждого перезапуска ждёт дольше, чтобы не оборвать медленную, но живую загрузку; после 8 перезапусков сдаётся и называет мод, на котором застряло;
- показывает статус на вашем языке вместо кракозябр сервера, какой мод сейчас качается, и честный прогресс-бар.

**С ZombieBuddy (по желанию):** 2.0.1 просила у Steam до 4 модов сразу; именно от этого и падает свой сервер, поэтому 2.0.2 снова качает по одному, а сервер, застывший внутри Steam, перезапускает через 30 с.

Мод клиентский: включите его в списке модов главного меню и один раз перезапустите игру; список модов сервера и другие игроки не затрагиваются. Настройки: Настройки > Моды > Download Unstuck.

**Стоял Host Unstuck?** Ничего делать не нужно: id мода, jar и файл настроек остались HostUnstuck, включать и подтверждать заново не придётся.

- Steam Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
- GitHub (исходники и архив): https://github.com/druidqwerty/DownloadUnstuck

Код пишет нейросеть, а с меня — постановка задач, тесты и время. Спасибо всем, кто поддерживает!
