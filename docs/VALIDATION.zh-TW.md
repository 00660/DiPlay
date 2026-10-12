# 繁體中文支援驗證紀錄

[繁體中文首頁](../README.zh-TW.md) · [完整建置指令](BUILD.zh-TW.md#完整檢查)

日期：2026-10-12。分支：`codex/traditional-chinese-support`。驗證環境：Windows、專案 Gradle wrapper 9.5.0、JDK 25 工具鏈及 Android SDK 37.0。

## 本次變更的檢查

| 檢查 | 結果 |
| --- | --- |
| common 的 997 個字串、shared 的 2 個字串 | 繁體中文資源鍵與格式參數相符，沒有重複鍵 |
| Home 的 21 個字串、map host 的 14 個字串 | 英文與繁體中文資源鍵及格式參數相符 |
| 語言、翻譯及設定畫面相關測試 | 24 項通過；包含新舊 Android 的語言選擇，以及窄／完整視窗、高密度、1.3 倍字體的文字省略檢查 |
| Home 返回操作測試 | 6 項通過，包含繁體中文、編輯模式與應用程式清單操作 |
| mobile／home／maphost lintDebug | 三個模組通過 |
| mobile／home／maphost assembleDebug | 三個 debug APK 建置通過；一般原始碼建置不含配件認證素材 |
| 靜態網站產生 | 8 個語系頁面產生成功，繁體中文入口連到中文文件 |
| 文件連結與標題錨點 | 繁體中文文件內的本機目標與錨點可以解析 |
| 公開原始碼與差異檢查 | `scripts/check_public_tree.py` 及 `git diff --check` 通過 |

## 完整 CI 尚未通過

已執行 `AGENTS.md` 指定的九個任務，並以 `--continue` 保留其他任務的結果。完整單元測試在此 Windows 環境仍有下列失敗；這些測試及其受測實作未在本次繁體中文變更中修改：

| 測試類別 | 失敗數 | 觀察到的錯誤 |
| --- | --- | --- |
| `HotspotJoinNormalizedTransactionTest` | 3 | 預期套用／回復結果，實際得到 `STORAGE_FAILED` |
| `UsbPermissionSetupTest` | 3 | 使用 Git 的 sh 後，shell 指令出現引號解析／`;;` 語法錯誤；未加入 sh 時則找不到執行檔 |
| `UpdateInstallCompatibilityTest` | 4 | FileProvider 無法找到包含 Windows 暫存 APK 路徑的已設定根目錄 |

完整執行的 shared 測試為 1,366 項、3 項失敗；common 為 1,166 項、7 項失敗。最後另以繁體中文相關的 24 項測試、Home 完整 6 項測試及三個應用程式的 lint／APK 建置驗證最終變更，該次 Gradle 執行為 `BUILD SUCCESSFUL`。這不等同於完整 CI 已通過。

本機完整執行紀錄位於 `.gradle/traditional-chinese-validation.log`；common 完整報告副本位於 `.gradle/traditional-chinese-full-tests/common/`。這些是忽略的本機產物，不提交到 Git。

## Android 手機實機檢查

同日透過已授權的 USB ADB 在 V2130 手機上安裝 `mobile-debug.apk`（套件 `com.shihab.diplay.hudtest`，版本 `0.2.18-hud-test`），安裝結果為 `Success`。

手機使用 Android 14（API 34）、1080 × 2400 螢幕、440 dpi，系統語言為 `zh-Hant-TW`。本次維持手機原本的螢幕、字體及系統語言設定。

- 設定概覽、語言頁及進階設定可正常開啟，介面使用繁體中文。
- 語言選單顯示「繁體中文」，套用後設定列顯示「應用程式語言 · 繁體中文」。
- 強制停止並重新啟動後，Android LocaleManager 回報 `[zh-TW]`，語言選擇保留。
- 開啟音訊焦點後，新增「暫時失去音訊焦點時將媒體靜音」及完整說明可見；已檢視實機截圖，這兩個新增字串沒有截斷。
- 檢查後將音訊焦點恢復為原本的關閉狀態，保留繁體中文選擇，並將手機停在設定概覽。

實機截圖：[繁體中文語言頁](screenshots/zh-TW/android14-language.png)、[進階音訊焦點](screenshots/zh-TW/android14-audio-focus.png)。

此原始碼 debug APK 沒有配件認證素材，因此會顯示「無法載入 CarPlay 認證」；這是預期限制，不影響上述介面檢查。尚未進行實車或 iPhone／CarPlay 連線驗證，也尚未發布網站。手機實測與自動化文字省略檢查不能保證每種字型與車機都沒有顯示問題。
