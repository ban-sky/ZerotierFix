# 签名与覆盖安装

CI 里 debug 和 release 使用同一把密钥签名，所以每次构建出来的包都能直接覆盖安装。
签名来源按下面的优先级选择（`ci/ci-build.sh`）：

1. **仓库 secret `ZT_KEYSTORE_BASE64`**（如果配置了，私钥不落仓库，最安全）。
2. **`keystore/zerotierfix.b64`**：仓库内公开的发布密钥，base64 编码的 PKCS12。
   目前用的就是这一份，保证 CI 每次签名一致。
3. 两者都没有时退回 AGP 的调试密钥（每次构建签名不同，只能先卸载再装）。

签名参数默认值：

| 项 | 默认值 | 可覆盖 |
| --- | --- | --- |
| keystore 类型 | `PKCS12` | `ZT_STORE_TYPE` |
| key alias | `zerotierfix` | `ZT_KEY_ALIAS` |
| store password | `zerotierfix` | `ZT_STORE_PASSWORD` |
| key password | `zerotierfix` | `ZT_KEY_PASSWORD` |

`app/build.gradle` 里的 `signingConfigs.fixed` 同时用于 `debug` 和 `release`，并显式打开
`enableV1Signing` / `enableV2Signing` / `enableV3Signing`：v1 让 Android 6 及更老的系统
能校验，v2 供 7.0–13 使用，v3 供 9+ 的密钥轮换校验，三者齐全才能从任意旧版本覆盖安装。

## 仓库里的密钥是公开的

`keystore/zerotierfix.b64` 解码后就是一把私钥，任何人都能拿到。它只解决「签名固定、
可以覆盖安装」，**不能**防止别人用同一把密钥签出冒名的包。如果要对外正式分发，建议：

1. 本地用 `keytool -genkeypair` 生成新密钥（本机需要 JDK）；
2. `base64 -w0 zerotierfix.p12` 后放进 GitHub Actions secret `ZT_KEYSTORE_BASE64`；
3. 同时配置 `ZT_KEY_ALIAS` / `ZT_STORE_PASSWORD` / `ZT_KEY_PASSWORD`；
4. 在 `.github/workflows/build.yml` 的 Build 步骤加：

```yaml
      - name: Build
        env:
          ZT_KEYSTORE_BASE64: ${{ secrets.ZT_KEYSTORE_BASE64 }}
          ZT_KEY_ALIAS: ${{ secrets.ZT_KEY_ALIAS }}
          ZT_STORE_PASSWORD: ${{ secrets.ZT_STORE_PASSWORD }}
          ZT_KEY_PASSWORD: ${{ secrets.ZT_KEY_PASSWORD }}
        run: bash ci/ci-build.sh
```

配置好 secret 后它比仓库里的 b64 优先，届时可以删掉 `keystore/zerotierfix.b64`。
注意：换密钥后，已安装的旧版本必须先卸载一次才能装新包（签名不同）。

## 一次性引导

如果 `keystore/zerotierfix.b64` 还不存在，创建一个空文件 `keystore/.bootstrap` 后推一次
CI，脚本会用 `keytool` 生成 PKCS12，并把 base64 放到产物里的 `dist/zerotierfix-keystore.b64`；
把它复制成 `keystore/zerotierfix.b64`、删掉 `.bootstrap` 再推一次即可。
