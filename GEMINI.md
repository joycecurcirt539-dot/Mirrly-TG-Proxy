# Rules & Preferences for Mirrly TG Proxy

## GitHub Release & Security Verification Rules
При публикации каждого нового релиза на GitHub (через `gh release create` или `gh release edit` / `gh release upload`), **ОБЯЗАТЕЛЬНО** указывать в тексте описания релиза (notes/release body) контрольные отпечатки SHA-256:
1. SHA-256 отпечаток ключа подписи разработчика (Keystore SHA-256).
2. SHA-256 хеши файлов бинарников (`app-release.apk` и `app-debug.apk`).

*Причина*: Встроенная в приложение система автообновления и проверки подлинности ([UpdateChecker](file:///c:/Users/iplii/OneDrive/Desktop/Mirrly%20dev/Mirrly%20TG%20Proxy/core/src/main/java/com/mirrly/tgproxy/core/UpdateChecker.kt) / [SignatureVerifier](file:///c:/Users/iplii/OneDrive/Desktop/Mirrly%20dev/Mirrly%20TG%20Proxy/app/src/main/java/com/mirrly/tgproxy/util/SignatureVerifier.kt)) считывает описание релиза через GitHub API и сверяет SHA-256 отпечатки для валидации официальности сборки.
