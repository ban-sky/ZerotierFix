# 固定签名 keystore

把用于给 APK 签名的 keystore 放到这个目录，文件名用 `zerotierfix.jks`（或
`zerotierfix.p12`），之后 `assembleDebug` 和 `assembleRelease` 都会用同一把
密钥签名，每次 CI 构建出来的包都能覆盖安装。

默认按 Android 调试密钥的约定读取：

| 项 | 默认值 | 可用 Gradle 属性覆盖 |
| --- | --- | --- |
| key alias | `androiddebugkey` | `-PZT_KEY_ALIAS=` |
| store password | `android` | `-PZT_STORE_PASSWORD=` |
| key password | `android` | `-PZT_KEY_PASSWORD=` |

没有这个文件时构建仍可进行：debug 包用 AGP 默认的调试密钥，release 包未签名，
且 CI 不会发布未签名的 release 包。

生成一个（本机需要 JDK）：

```bash
keytool -genkeypair -v -keystore zerotierfix.jks -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"
```

注意：仓库是公开的，这把私钥也会公开。它只能保证“签名一致、可以覆盖安装”，
不能防止别人用同一把密钥签出冒名的包；如果要对外分发，建议改成用仓库 secret
保存的正式签名密钥。
