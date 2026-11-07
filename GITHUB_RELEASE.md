# GitHub 自动编译与发布 APK

本项目继续走轻量构建：Python 3 + JDK 17 + Android Platform 35 / Build Tools 35.0.0。
GitHub 使用 Ubuntu runner，不下载 Android Studio、Gradle、模拟器、NDK 或 Node 依赖。

## 1. 必须配置的 Variables / Secrets

打开仓库 **Settings → Secrets and variables → Actions**。以下均为 **Repository** 级配置。
不要放到 Environment 级配置，因为本工作流没有绑定 Environment。

### Variables（3项）

| 名称 | 值 |
|---|---|
| `CHECKWAKEUP_REPORT_URL` | 默认上报地址。可填 HTTP(S) 根地址，构建时自动补 `/ingest/v1/reports`；也可直接填完整接口地址。禁止账号、query 和 fragment。 |
| `CHECKWAKEUP_KID` | 与服务器 `INGEST_ACTIVE_KID` 对应的密钥编号；1–64位字母、数字、`_`、`-`。 |
| `CHECKWAKEUP_PUBLIC_KEY_PEM` | 服务器的 RSA-3072 或更长的 **SPKI 公钥 PEM 全文**，包括 `BEGIN PUBLIC KEY` / `END PUBLIC KEY` 两行，保留真实换行。不要填写私钥。 |

公钥也可以改放 **同名 Secret**，工作流优先使用 Secret，再使用 Variable。
地址和 kid 使用 Variables。上述配置在编译时写入生成的 Java 源码，打包进 APK，
不会在手机运行时从 HTTP 下载配置。公钥是公开信息；即使使用 Secret，APK 中仍可提取，
不能把这种注入当作“在手机里隐藏秘密”。
默认地址仅影响未保存上报地址的安装；升级不会覆盖手机里已经自行保存的 URL、名称和开关。

当前本机原有上报配置已保留在被忽略的 `.config/build.json` 与
`.config/report-public.pem`，配置 GitHub 时从这些文件复制，**无需更换服务器密钥**。
不要将服务器私钥、管理员密码或后端 `SECRET_KEY` 配置到 Android 工作流。

### Secrets（4项，均必填）

| 名称 | 值 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | **现有** `.signing/local-test.p12` 文件的 Base64 内容。不是证书指纹，也不是后台加密公钥。 |
| `ANDROID_KEYSTORE_PASSWORD` | 该 keystore 的密码。当前历史本地测试证书是 `android`。 |
| `ANDROID_KEY_PASSWORD` | 该 alias 的私钥密码。当前历史本地测试证书是 `android`。 |
| `ANDROID_KEY_ALIAS` | 当前历史证书 alias 为 `androiddebugkey`。 |

签名私钥文件必须保密。上面的历史测试密码本来就是公开测试密码，**不要把这张证书当成商店生产证书**。
这里保留它，是为了让已经安装旧 APK 的手机能够覆盖更新。换一张全新证书，会失去普通覆盖升级兼容。

如果已安装 GitHub CLI，可以直接把原文件通过标准输入写入 Secret，不用打印或另存 Base64：

```sh
gh auth login
# 若没有设置 git remote，可给 gh 命令增加 --repo OWNER/REPO
base64 < .signing/local-test.p12 | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_PASSWORD
gh secret set ANDROID_KEY_ALIAS
```

后三条交互输入对应值。如果不使用 CLI，在本机生成 Base64 后填入网页 Secret，
不要粘贴到 Issue、日志、README 或提交记录里。Base64 只是编码，不是加密。

## 2. 签名防错与覆盖安装

原 0.3.1 APK 签名证书的 SHA-256 已固定在 `config/signing-certificate.sha256`：

```text
5bce2f9dd58a82ed2be4622c72a0943aee5bdef914f7288e489c44103ef5bb21
```

- 缺少配置、找不到证书、密码错误或证书不匹配，构建直接失败；**不会自动生成替代证书**。
- 编译前验证 alias 对应证书，签名后再次用 `apksigner verify` 验证。
- 签名密码通过进程环境传给工具，不作为命令行参数打印。
- CI 解码后的 keystore 仅写到 `RUNNER_TEMP`，权限 0600；构建后执行清理。
- Artifact / Release 仅包含 APK、APK SHA-256 和公开构建信息，不上传 keystore、配置源文件或整个工作目录。
- 默认产出非 debuggable 的 release APK，但签名与旧本地 debug APK 相同；
  包名仍为 `dev.checkwakeup`，版本号由 `version.json` 统一控制。

不要为“让构建通过”而改掉证书指纹。必须上传原 `.p12` 文件，并保留离线备份；
仅凭 APK 中的公开证书无法恢复签名私钥。升级时覆盖安装，**不要先卸载**。

## 3. 触发方式

工作流：`.github/workflows/android-release.yml`，名称 **Build and release APK**。

| 操作 | 行为 |
|---|---|
| Pull Request | 只做无需 Secrets 的静态、JVM、构建配置和 SQL 检查，不签名、不发布。 |
| Push 到 `main` / `master` | 检查通过后构建签名 APK，上传 Actions Artifact，不创建 Release。 |
| Actions → Run workflow | 手动构建；选分支时只生成 Artifact，选匹配的版本 tag 时也会发布 Release。工作流须先存在于默认分支。 |
| Push `v*` tag | 检查、编译、签名、上传 Artifact，再自动发布 GitHub Release。 |

例如当前版本是 `version.json` 中的 `0.3.2` / `code: 5`：

```sh
# 将本次代码、工作流、模板和公开测试夹具全部提交；不要提交 .config / .signing。
git add -A
git status
git commit -m "Add injected build config and signed APK releases"
git push origin main       # 若默认分支是 master，使用 master
git tag v0.3.2
git push origin v0.3.2
```

之后发布新版本：只修改 `version.json` 的 `name`，并将 `code` **递增**，提交后再推同名 tag。
tag 必须严格等于 `v` + `name`，例如 `v0.3.3`，否则构建失败。
同一个 tag 不允许静默覆盖已有 Release；发布采用先创建 draft 并上传附件、再转正式的流程。
如果发布中途留下 draft，需要先人工检查、处理 draft，再重新运行，不自动覆盖旧附件。

Artifact 保留14天；正式 Release 附件不受这个 Artifact 保留期限控制。
仓库/组织策略需要允许工作流的 `GITHUB_TOKEN` 写入仓库内容；无需另建 PAT。
工作流默认只读，只有非 PR 的 APK job 申请 `contents: write` 用于发布。
不要改成 `pull_request_target` 后执行 PR 提交的代码，不要向不可信分支提供签名 Secrets。

## 4. 本地构建

本机原配置已经迁移到 `.config/`，可继续直接执行：

```sh
python3 scripts/check-static.py
bash scripts/test-core.sh
bash scripts/test-upload.sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
bash scripts/build.sh
```

新电脑先参考 `config/build.example.json`、`config/signing.example.json`，
创建 `.config/build.json`、`.config/signing.json`，并安全恢复原签名文件。
`public_key_file` / `keystore_file` 的相对路径以各自 JSON 所在目录为准。
不要把 example 中的占位值当正式配置；公钥必须是与服务器私钥配对的那一份。

也可不创建 JSON，完全使用环境变量：

```text
CHECKWAKEUP_REPORT_URL
CHECKWAKEUP_KID
CHECKWAKEUP_PUBLIC_KEY_PEM
ANDROID_KEYSTORE_FILE
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_PASSWORD
ANDROID_KEY_ALIAS
```

本地环境变量优先于 JSON；显式空值会报错，不回退。可以通过
`CHECKWAKEUP_CONFIG` / `CHECKWAKEUP_SIGNING_CONFIG` 指定其他 JSON 文件。
GitHub Actions **只使用注入的环境变量，忽略本地 JSON 回退**。
PEM 环境变量需要真实换行，不是字面量 `\n`。

输出：

```text
dist/checkwakeup-0.3.2-release.apk
dist/checkwakeup-0.3.2-release.apk.sha256
dist/build-info.json
```

需要 debuggable 包时使用 `bash scripts/build.sh --debug`，仍必须使用原证书。
标准 AGP 工程也使用同一个配置生成器和 `version.json`，生成器需要 Python 3；
为防止 Android Studio 自动换用另一张 debug 证书，AGP debug/release 均不自动签名，
只用于源码导入/编译。需要可安装且能覆盖升级的包，请使用 `scripts/build.sh`；
GitHub 正式发布也只走这一路径。

## 5. 提交前检查与验证边界

`.config/`、`.signing/`、`.cache/`、`.toolchain/`、`dist/`、`**/build/`、
私钥/keystore 扩展名与 `.env` 都已忽略。**已经被 Git 跟踪的文件不受新增 ignore 规则保护**，
提交前检查 `git diff --cached` / `git ls-files`。如果曾暂存旧源码，本次提交前重新
`git add -A`，避免仍把旧暂存版本提交。

`tests/fixtures/test-private.pem` 是有意公开的测试密钥，只用于无 Secrets 的 JVM/本机隔离测试，
禁止在正式部署使用；APK 构建没有测试公钥回退。它不是 APK 签名私钥。
这两个公开 fixture 是 PEM 忽略规则中仅有的例外，CI 不依赖旁边的后端目录。

本地已验证的内容见 `dist/verification-0.3.2.txt`。GitHub workflow 本身尚需
你推送仓库、配置上述 Variables/Secrets 后首次真实运行验收；本地检查不能替代 GitHub 托管环境运行。
没有自动上传到你的 GitHub、配置 Secrets、推送 tag、修改线上后端或安装手机。
