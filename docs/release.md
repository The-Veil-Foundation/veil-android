# Публикация и релизы

## 1. SSH-ключ для git

Ключ уже создан на этой машине:

```
~/.ssh/id_ed25519          приватный — никому не отправлять
~/.ssh/id_ed25519.pub      публичный — вставляется в GitHub
~/.ssh/config              уже настроен на github.com
```

Публичный ключ:

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMXIVk6qlMw3/D70DjUB8wsFzC6768Fn5PgP56Ny4PqP veil-android@local
```

Добавить: GitHub → Settings → SSH and GPG keys → New SSH key → Authentication key,
вставить строку выше.

Проверка:

```bash
ssh -T git@github.com
# Hi <ник>! You've successfully authenticated...
```

## 2. Репозиторий

Создать пустой репозиторий в организации `The-Veil-Foundation`:
https://github.com/organizations/The-Veil-Foundation/repositories/new
(имя `veil-android`, без README и .gitignore).

Remote и адреса в коде уже указывают на организацию:

```bash
git remote -v
# origin  git@github.com:The-Veil-Foundation/veil-android.git
```

Если аккаунт `s4ntiksu` не может писать в организацию, ключ можно добавить
прямо в репозиторий: Settings → Deploy keys → Add deploy key → вставить
публичный ключ и включить «Allow write access».

## 3. Первый пуш

```bash
cd "/home/me/Рабочий стол/veil-android"
git push -u origin HEAD:main
```

## 4. Ключ подписи релизов

Создан отдельно от git-ключа:

```
~/.local/veil/veil-release.jks     сам ключ (бэкапить!)
~/.local/veil/veil-release.pass    пароль
~/.local/veil/veil-release.b64     тот же ключ в base64 для секрета
```

Данные: alias `veil`, пароль — в файле `veil-release.pass`.
Без этого файла обновления «поверх» установленной версии ставить нельзя:
Android требует одинаковую подпись.

Секреты репозитория (Settings → Secrets and variables → Actions → New repository secret):

| Имя | Значение |
|---|---|
| `VEIL_KEYSTORE_BASE64` | содержимое `~/.local/veil/veil-release.b64` |
| `VEIL_KEYSTORE_PASSWORD` | пароль из `veil-release.pass` |
| `VEIL_KEY_ALIAS` | `veil` |
| `VEIL_KEY_PASSWORD` | тот же пароль |

Скопировать base64 в буфер:

```bash
cat ~/.local/veil/veil-release.b64 | xclip -selection clipboard   # или открой файл и скопируй
```

## 5. Релиз

Автоматических сборок нет: ни на push, ни на тег. Сборка запускается только
вручную.

1. Поднять версию в `composeApp/build.gradle.kts`:

```kotlin
versionCode = 3          // всегда больше предыдущего
versionName = "0.1.1"    // тег будет v0.1.1
```

2. Закоммитить и запушить `main`.
3. GitHub → **Actions** → **Release** → **Run workflow** → в поле `tag` ввести
   `v0.1.1` (должен совпадать с `versionName` без `v`, иначе job упадёт на шаге
   «Check versions match»). При желании включить `draft`.
4. Через несколько минут в Releases появятся:

- `veil-0.1.1-arm64-v8a.apk`
- `veil-0.1.1-x86_64.apk`
- `veil-0.1.1-universal.apk`

Тег можно не пушить вручную: GitHub создаст его сам при публикации релиза.

Чтобы проверить сборку без релиза: **Actions** → **CI** → **Run workflow**.

## 6. Локальная сборка релиза

```bash
export JAVA_HOME="$HOME/.local/jdk" ANDROID_HOME="$HOME/Android"
VEIL_KEYSTORE="$HOME/.local/veil/veil-release.jks" \
VEIL_KEYSTORE_PASSWORD="$(cat ~/.local/veil/veil-release.pass)" \
VEIL_KEY_ALIAS=veil \
VEIL_KEY_PASSWORD="$(cat ~/.local/veil/veil-release.pass)" \
./gradlew :composeApp:assembleRelease -PabiSplits
```

Без переменных релиз подписывается debug-ключом — для альфы годится, но такие
APK не встанут поверх ранее установленных с другим ключом.
