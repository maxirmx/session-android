# Noth Android

[![Noth CI](https://github.com/noth-messenger/noth-android/actions/workflows/build.yml/badge.svg)](https://github.com/noth-messenger/noth-android/actions/workflows/build.yml)
[![Release](https://github.com/noth-messenger/noth-android/actions/workflows/release.yml/badge.svg)](https://github.com/noth-messenger/noth-android/actions/workflows/release.yml)

[Скачать APK можно здесь](https://github.com/maxirmx/noth-android/releases/latest)

## Краткое описание

Noth напрямую интегрируется с [узлами службы Oxen](https://docs.oxen.io/about-the-oxen-blockchain/oxen-service-nodes), которые представляют собой распределённые, децентрализованные b pfobo`yyst epksузлы.
Узлы службы действуют как серверы, хранящие сообщения в офлайн-режиме, а также как сеть узлов, обеспечивающих маршрутизацию через сеть Onion сеть, скрывая IP-адреса пользователей.
Полное описание работы можно найти в [документе Session Whitepaper](https://getsession.org/whitepaper).

## Требования для сборки

Для локальной сборки приложения Noth Android требуются:

### Основные инструменты
- **JDK 17** - Java Development Kit (требуется версия 17)
- **Git** - для клонирования репозитория и управления субмодулями
- **C++ Toolchain** - включая cmake и ninja для сборки нативных компонентов

### Android SDK
- Android SDK Build Tools (cmdline tools version: 9123335)
- SDK Platform для всех используемых API уровней (минимум API 26, целевой API 34)

### Дополнительные зависимости
- Субмодули Git - после клонирования репозитория выполните:
  ```
  git submodule update --init --recursive
  ```
### Другие зависимости
Все остальные зависимости будут загружены и установлен автоматически.

## Инструкция по выпуску новой версии

Для приложения Noth Android развёрнута автоматическая система тестирования и сборки на базе GitHub Actions
Для выпуска новой версии необходимо
1. Создать `branch` или `fork`  и запрограмировать в нём, всё, что хочется запрограммировать
2. Cоздать `Pull Request (PR)`. Для PR будет отрабатывать `workflow`, которое включает в себя Unit Test'ы и сборку отладочной и релизной версии. Указанные версии можно тестировать вручную или автоматически.
3. По готовности версии необходимо:
- сделать `merge` в `main`
- в файле `build.gradle` изменить значения `canonicalVersionCode` и `canonicalVersionName`. `canonicalVersionCode` рекомендуется увеличивать на 1.  `canonocalVersionName` можно менять, как нравится, но она должна соотвествовать схеме `semantic versioning` - XX.YY.ZZ, например 2.13.7
- поставить метку, соответствующую canonicalVersionCode.
  Например, для 2.13.7 нужно вызывать
  ```
  git tag v2.13.7
  git push --tags
  ```
4. После установки метки автоматически запустятся релизные процедуры. На базе метки будет создан релиз, дял релиза будет собран файл apk и выложен в качестве `release asset`.
