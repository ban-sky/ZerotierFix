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

## 更安全的方式：keystore 放到仓库 secrets

推荐把私钥放在 GitHub Actions secrets 里，仓库只保留这个说明文件。构建流程
（`ci/ci-build.sh`）会在 `ZT_KEYSTORE_BASE64` 存在时把它解码到 runner 的临时目录、
用完即删，再通过 `ZT_STORE_FILE` 交给 Gradle 签名。

仓库需要配置四个 secret（Settings → Secrets and variables → Actions）：

| secret | 内容 |
| --- | --- |
| `ZT_KEYSTORE_BASE64` | `base64 -w0 zerotierfix.jks` 的输出 |
| `ZT_KEY_ALIAS` | 例如 `zerotierfix` |
| `ZT_STORE_PASSWORD` | keystore 密码 |
| `ZT_KEY_PASSWORD` | key 密码 |

并在 `.github/workflows/build.yml` 的 Build 步骤上加环境变量：

```yaml
      - name: Build
        env:
          ZT_KEYSTORE_BASE64: ${{ secrets.ZT_KEYSTORE_BASE64 }}
          ZT_KEY_ALIAS: ${{ secrets.ZT_KEY_ALIAS }}
          ZT_STORE_PASSWORD: ${{ secrets.ZT_STORE_PASSWORD }}
          ZT_KEY_PASSWORD: ${{ secrets.ZT_KEY_PASSWORD }}
        run: bash ci/ci-build.sh
```

这样 debug 和 release 都由同一把私钥签名，连续构建可以互相覆盖安装，而私钥不会
出现在仓库或构建日志里。注意：一旦更换密钥，已安装的旧版本必须先卸载一次。
