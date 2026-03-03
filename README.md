# 智能卡 (Android)

一个基于 Toporin `Satochip` 库实现的 Android 离线冷签应用，用于给 TokenPocket (TP) 观察钱包的 EVM 请求做扫码签名。

## 功能

- 支持解析 TP `ethereum:signTransaction` 二维码协议
- 支持解析 TP `ethereum:personalSign`（兼容 `signPersonalMessage`）
- 支持解析 TP `ethereum:signTypeData` / `signTypeDataV4`（兼容 `signTypedData` / `signTypedDataV4`）
- 支持 TP `tp:multiFragment` 动态分片拼接
- 支持多分片二维码连续扫码（遇到分片会自动继续扫码直到拼接完成）
- 通过 NFC 连接 Satochip 卡并调用 `cardSignTransactionHash`
- 本地完成 EVM 交易编码（Legacy + EIP-1559，含 Arbitrum One / chainId 42161）与 EIP-191 / EIP-712 哈希
- 生成对应 `...Signature` 回传字符串和二维码
- 不申请 `INTERNET` 权限，按离线扫码冷签场景设计

## 项目结构

- `app/`: Android 应用（仅扫码 + NFC 签名）
- `satochip-lib/`: 从 Toporin `Satochip-Java` 复制并改造的本地库模块
- `card-applet/SatochipApplet/`: Satochip JavaCard CAP 编译源码（来源于 Toporin `SatochipApplet`）
- `card-applet/CAP_BUILD_AND_INSTALL.zh-CN.md`: CAP 编译、安装、验证步骤（中文）
- `card-applet/TAILS_SATOCHIP_UTILS_OFFLINE_GUIDE.zh-CN.md`: Tails OS 离线用 Satochip-Utils 设置 PIN + 导入助记词教程
- `card-applet/prebuilt/`: 已编译 CAP 固件与校验值（可直接下载安装）
- `backups/satochip-utils/`: Toporin `Satochip-Utils` 全量离线备份（git bundle）

## 编译

1. 安装 Android Studio（建议 Hedgehog 及以上）。
2. 打开目录：`tp-satochip-signer`
3. 确认 `local.properties` 中 `sdk.dir` 指向你的 Android SDK。
4. 构建：

```bash
./gradlew :app:assembleDebug
```

调试 APK 路径：

- `app/build/outputs/apk/debug/app-debug.apk`

首次安装建议先卸载历史包（避免签名冲突）：

```bash
adb uninstall com.tpsigner
adb uninstall com.smartcard.signer
```

## 使用流程（Android）

1. 输入卡 PIN 与 BIP32 路径（默认 `m/44'/60'/0'/0/0`）。
2. 点击“解锁显示地址”，将智能卡贴到手机 NFC 区域。
3. 解锁成功后点击“扫码”读取 TP 签名请求（扫码为竖屏）。
4. 先核对“转账/合约信息”和“DApp 信息”，点击“已核对，允许签名”。
5. 点击“贴卡签名”，再次贴卡完成签名。
6. TP 扫描本应用展示的回传二维码完成广播。

## 启动与排错

- 正确启动组件：`com.smartcard.signer/.MainActivity`
- ADB 启动命令：

```bash
adb shell am start -n com.smartcard.signer/.MainActivity
```

- 如果“传到手机点安装”失败，而 ADB 可安装，通常是旧版本签名冲突，先卸载再装。

## 助记词导入策略

安卓端不提供助记词导入。建议在离线 Tails 电脑导入，再用本应用做扫码签名。

## 在离线 Tails 电脑导入助记词（建议）

可使用 Toporin 的桌面库（`Satochip-Java` 的 `satochip-lib + satochip-desktop`）完成导入：

1. 离线环境准备 Java + PC/SC 读卡器（CCID）。
2. 使用桌面程序连接卡，先 `cardSelect("satochip")` 和 `cardVerifyPIN(pin)`。
3. 在离线机把 BIP39 助记词转 seed（可选 passphrase）。
4. 调用 `cardBip32ImportSeed(seed)` 写入卡。
5. 再调用 `cardBip32GetExtendedKey("m/44'/60'/0'/0/0", null, null)` 校验地址。

注意：导入助记词属于高敏操作，建议只在完全离线、可信电脑执行。

## CAP 编译与写卡

本仓库已经包含 CAP 编译源码与操作说明：

- 源码目录：`card-applet/SatochipApplet/`
- 中文步骤文档：`card-applet/CAP_BUILD_AND_INSTALL.zh-CN.md`

可按文档在离线 Linux/Tails + ACR39U 上完成：

1. 编译 `SatoChip-3.0.4.cap`
2. 用 `GlobalPlatformPro (gp.jar)` 安装到 J3R180 白卡
3. 用 `opensc-tool` 执行 `SELECT AID` 验证安装

如果你不改源码，也可直接使用预编译固件：

- `card-applet/prebuilt/SatoChip-3.0.4.cap`
- `card-applet/prebuilt/SHA256SUMS.txt`

## Tails 离线导入教程（Satochip-Utils）

如果你使用 `Satochip-Utils` 图形界面来恢复助记词，请看：

- `card-applet/TAILS_SATOCHIP_UTILS_OFFLINE_GUIDE.zh-CN.md`

文档包含：

1. 离线环境准备
2. 先设置 PIN（`Setup my card`）
3. 再导入助记词（`Setup Seed`）
4. 成功校验与常见问题

## Satochip-Utils 备份（防删库）

本项目已内置 Satochip-Utils 备份文件，路径：

- `backups/satochip-utils/Satochip-Utils-backup-20260303.bundle`
- `backups/satochip-utils/README-BUNDLE.md`

可用以下命令恢复仓库：

```bash
git clone backups/satochip-utils/Satochip-Utils-backup-20260303.bundle Satochip-Utils-restore
```

## 当前范围

当前已实现：

- `signTransaction`
- `personalSign` / `signPersonalMessage`
- `signTypeData` / `signTypeDataV4`（兼容 `signTypedData` / `signTypedDataV4`）

暂未实现（可继续扩展）：

- `signTypedDataLegacy`（数组格式）
- BTC/Tron/Solana 等链
- 回传分片动态二维码播放
