# Pi Zero 独立离线签名器（无电脑）

本目录提供树莓派独立运行方案：

- 开机自启 kiosk UI
- 按键输入 PIN（无需键盘）
- OV5647 扫码读取 TP 请求
- ACR39U + 智能卡离线签名
- ST7789 屏幕展示回传二维码

## 按键定义（Waveshare 1.3inch LCD HAT 常见引脚）

- 摇杆：`UP=6 DOWN=19 LEFT=5 RIGHT=26 PRESS=13`
- 按键：`KEY1=21 KEY2=20 KEY3=16`

## 使用流程

1. 上电开机后直接进入扫码页面。
2. 相机扫 TP 请求二维码（支持 `tp:multiFragment` 分片拼接）。
3. 扫码完成后进入 PIN 页面，用方向键 + PRESS/KEY2 选择数字，输入 PIN，点 `OK`。
4. 屏幕提示“请插卡”，此时再插入智能卡，系统自动完成解锁并签名。
5. 签名成功后显示 TP 回传二维码。

## 安装（在 Pi 本机执行）

从发布包根目录执行：

```bash
sudo bash pi-appliance/setup/install_standalone.sh
sudo reboot
```

## 说明

- 当前是 240x240 小屏，长交易回传二维码可能较密。
- 若扫码困难，可后续扩展“回传分片轮播二维码”。
- 运行期临时文件放在 `/run/tp-pi-kiosk`（tmpfs），断电后不会保留请求与回传内容。
