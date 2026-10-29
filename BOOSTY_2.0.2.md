# Пост для Boosty: Download Unstuck 2.0.2 (исправление вылета 2.0.1). Публикация: workspace/tools/boosty/README.md, EN потом RU.

Title / Заголовок: Download Unstuck 2.0.2 — fix: the hosted server no longer shuts down / исправление: свой сервер больше не выключается

---

**Download Unstuck 2.0.2** for Project Zomboid (B42.21+) is out. If you use it with ZombieBuddy, please update.

**What was broken:** in 2.0.0 and 2.0.1 the hosted server shut down at "Downloaded 0 of N" whenever a mod had to be downloaded or updated. The mod asked Steam for several mods at once, and the game's Steam connection allows only one download at a time. My first test had nothing to download, so I missed it. Sorry!

**What 2.0.2 does:**

• downloads one mod at a time, like the game itself, and never crashes when Steam is busy;
• still restarts a stuck download for you, and restarts a server frozen inside Steam after 30 s;
• the second line under the progress bar again shows which mod is downloading and how many MB arrived.

There is no speed-up any more: with one download at a time it is not possible. What stays is the main thing: hosting no longer hangs forever on "Downloaded 259 of 308".

Tested in the game: a 308-mod pack hosted from the menu with ZombieBuddy, mods being downloaded and updated, the server reached the world.

• Steam Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
• GitHub (source and zip): https://github.com/druidqwerty/DownloadUnstuck

The code is written by an AI; the direction, the testing and the time are mine. Thanks to everyone who supports this!

---

Вышел **Download Unstuck 2.0.2** для Project Zomboid (B42.21+). Если вы ставили его вместе с ZombieBuddy, обновитесь.

**Что было сломано:** в 2.0.0 и 2.0.1 свой сервер выключался на «Загружено 0 из N», если хоть один мод нужно было скачать или обновить. Мод просил у Steam несколько модов сразу, а игровое подключение к Steam разрешает только одну загрузку за раз. В моей первой проверке скачивать было нечего, поэтому я это пропустил. Простите!

**Что делает 2.0.2:**

• качает моды по одному, как сама игра, и не падает, если Steam занят;
• как и раньше, сам перезапускает застрявшую загрузку, а сервер, застывший внутри Steam, перезапускает через 30 с;
• во второй строке под прогресс-баром снова видно, какой мод качается и сколько МБ пришло.

Ускорения больше нет: при одной загрузке за раз оно невозможно. Осталось главное: свой сервер больше не висит вечно на «Загружено 259 из 308».

Проверено в игре: модпак на 308 модов, свой сервер из меню с ZombieBuddy, моды скачивались и обновлялись, сервер дошёл до мира.

• Мастерская Steam: https://steamcommunity.com/sharedfiles/filedetails/?id=3829163351
• GitHub (исходники и архив): https://github.com/druidqwerty/DownloadUnstuck

Код пишет нейросеть, а с меня — постановка задач, тесты и время. Спасибо всем, кто поддерживает!
