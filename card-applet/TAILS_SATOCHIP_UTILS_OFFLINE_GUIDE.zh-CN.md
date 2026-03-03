# Tails OS 离线使用 Satochip-Utils：设置 PIN + 导入助记词

本教程用于你当前这套流程：

- 卡：J3R180（已安装 Satochip applet）
- 读卡器：ACR39U
- 工具：Toporin `Satochip-Utils`（桌面 GUI）
- 目标：在 **离线 Tails OS** 中完成 PIN 设置和助记词导入

## 0. 先决条件

1. 卡里必须已有 Satochip applet。  
如果是白卡且未安装 applet，先按：`card-applet/CAP_BUILD_AND_INSTALL.zh-CN.md`
2. 准备好 `Satochip-Utils` AppImage（建议提前在联网环境下载好并校验后拷入 U 盘）。
3. 准备好 12/24 词 BIP39 助记词（以及可选 passphrase）。

## 1. Tails 离线环境准备

1. 启动 Tails 后，不连接网络（或物理断网）。
2. 插入 ACR39U 读卡器和智能卡。
3. 终端确认读卡器与卡可见：

```bash
pcsc_scan
```

看到 `ACS ACR39U...` + `Card inserted` 即正常。

## 2. 启动 Satochip-Utils

假设 AppImage 在当前目录：

```bash
chmod +x ./Satochip-Utils*.AppImage
./Satochip-Utils*.AppImage
```

## 3. 第一步：设置 PIN（Setup my card）

在 Satochip-Utils 左侧菜单中：

1. 插卡后点击 `Setup my card`
2. 在页面 `Create your card PIN code` 输入：
   - `New PIN code`
   - `Confirm PIN code`
3. 点击 `Save PIN`

规则：

- PIN 长度 4-16 字符
- 可用数字/大小写/符号

成功后通常会出现提示 `Your card is now setup!`。

## 4. 第二步：导入助记词（Setup Seed / Import Seed）

PIN 设置完成后，左侧状态按钮会从 `Setup my card` 变成 `Setup Seed`。

1. 点击 `Setup Seed`
2. 在 `Import Seed` 页面选择：
   - `I already have a seedphrase`
3. 在输入框粘贴或手动输入助记词（12 或 24 词，英文空格分隔）
4. 如你使用 BIP39 passphrase：
   - 勾选 `Use a passphrase (optional)`
   - 填写 passphrase
5. 点击 `Import`
6. 根据提示输入 PIN 验证

成功后会提示 `Your card is now seeded!`。

## 5. 完成后验证

1. 左侧状态应显示 `Setup Done`
2. 你可以在安卓签名 App 中：
   - 输入 PIN 解锁
   - 贴卡
   - 看是否能正常显示地址并签名

## 6. 常见问题

### 6.1 提示 seed 无效

- 助记词必须是标准 BIP39（12/24 词）
- 单词拼写、顺序、空格必须准确

### 6.2 PIN 报错或被锁

- PIN 错误会递减剩余次数（Satochip 有 PIN 尝试次数限制）
- 多次错误可能会阻塞 PIN

### 6.3 已 setup/seed 的卡再次导入

- 已有 seed 的卡不能直接“覆盖导入”
- 需要先确认你的恢复策略，再考虑重置卡

## 7. 离线安全建议

1. 助记词只在离线 Tails 输入，不要联网输入。
2. 导入完成后，关闭应用并清理临时文件/剪贴板。
3. 不要拍照/截图助记词页面。
4. 建议额外准备纸质备份或硬件备份介质。

## 8. 关联文档

- CAP 安装：`card-applet/CAP_BUILD_AND_INSTALL.zh-CN.md`
- 安卓签名端：仓库根目录 `README.md`
