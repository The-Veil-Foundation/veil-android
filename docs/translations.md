# Таблицы переводов

`ui/translations/` содержит 5 460 переводов (546 ключей на язык), перенесённых из
`The-Veil-Foundation/veil-macos`, commit `42ce7684b50145dce18259e3843bcac900a3113b`,
`Sources/XrayClient/Core/LocalizationTable.swift`.

Источник: https://github.com/The-Veil-Foundation/veil-macos
Copyright (C) 2026 faustyu1. Лицензия AGPL-3.0, как и у Android-проекта;
полный текст находится в `LICENSE`.

Доступны RU/EN и 10 дополнительных языков macOS. Системный язык определяется по
коду локали; для арабского включено RTL-направление Compose. Android-строки,
которых нет среди ключей macOS, пока отображаются по-английски, а не по-русски.
Это перенос таблиц и механизма, не заявление о полном переводе всех Android-экранов.
Динамические строки требуют отдельных шаблонов; форматирование Swift не исполняется.
