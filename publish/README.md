# publish/ —— 要发出去的东西都在这（当前版本 0.0.1）

> 这个文件夹**在仓库里**（会随源码一起被提交），每次发版的 jar 也放这儿；下一版换文件即可（jar 名带版本号）。

> 一包到底：上传时只用这个文件夹里的文件。站点**字段怎么填 / 各站的坑 / 上传前自检** → KB `项目/桌游模拟器/发布清单.md`。

| 文件 | 什么用途 | 传到哪 |
|---|---|---|
| `tablegame-0.0.1.jar` | **本体**（1.4 MB）。构建时间见文件属性；包内已核对 `version="0.0.1"` · `license="MIT"` | Modrinth / CurseForge / MCMod 的文件上传 + GitHub Release |
| `icon-512.png` | 项目图标，512×512 透明底（游戏内「棋牌桌」物品图标抠底放大而来） | Modrinth、CurseForge **必需** |
| `icon-256.png` · `icon-128.png` | 小图 | 列表页缩略图 / MCMod |
| `description-en.md` | 英文长描述（功能清单 + 要求 + 安装 + 上手） | Modrinth / CurseForge **Description** 栏 |
| `description-zh.md` | 中文长描述 | MCMod 简介栏 |
| `summaries.md` | **一句话简介** 3 个版本（中英双语 / 纯英文 / 更短），字数已标 | Modrinth / CurseForge 的 **Summary** 栏（有字数上限，英文必需） |
| `changelog-0.0.1.md` | 首发更新日志 | 各站「Changelog / 更新日志」栏 |
| `LICENSE.txt` | MIT 全文（与仓库里的 `mod/LICENSE` 同一份） | 需要上传许可证文件时用；Modrinth 直接在下拉里选 `MIT License` |

## 发布前 30 秒自检

1. 本文件夹里的 `tablegame-0.0.1.jar` 时间戳 = 最近一次 `./gradlew build` 的时间（**改过代码/文案后要重新构建并覆盖这份拷贝**）。
2. 三处版本号一致：`mod/gradle.properties` 的 `mod_version` = jar 内 `version` = 站点表单里填的 `0.0.1`。
3. 三处许可证一致：`mod_license=MIT` = jar 内 `license` = 站点下拉选的 `MIT License`。

## 还没有的东西（发布不阻塞）

- 截图（你说了先不整；Modrinth 的资源包类目才强制画廊图，mod 不强制）
- 源码仓库链接（仓库刚建，推上去后把 URL 填进 Source 字段，并可在两份描述里补一句「源码在 GitHub」）
