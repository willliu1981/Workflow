# TaskFlow (Workflow DSL) - Java 17 Minimal Interactive Workflow Engine

這是一個「資料驅動流程引擎」的最小教學示範：  
用 XML 描述流程（DSL），用 XSD 驗證資料正確性，Java 引擎負責執行（含互動 choice）。

目前提供 Console 互動版本；未來可無痛替換為 LibGDX UI / Android UI 的 Presenter。  
此外也加入 **Effect Bridge Demo**：示範如何把 workflow 的結果「接軌到 App / GameControl 的實際效果」（獎勵、旗標、狀態更新等）。

---

## 特色

- **DSL（XML）**：流程寫在資料裡，不把分支/關卡寫死在程式碼
- **Validation（XSD）**：載入期就擋掉不合法 XML（fail-fast）
- **Engine（Java）**：以 state（variables）+ tasks（TaskDefinition）執行流程
- **互動 choice**：Console 透過輸入 1/2 進行選擇（Presenter 可替換）
- **Effect Bridge（副作用橋接）**：新增 `effect` task，透過 `EffectHandler` 把流程結果導向 App/遊戲邏輯
- **可擴充 task type**：新增任務只要擴充 XSD + Engine switch

---

## 目前支援的 task type

| type    | 用途 |
|--------|------|
| setVar  | 寫入變數（state） |
| log     | 印出訊息（支援 `${var}` 插值） |
| choice  | 顯示兩個選項，使用者選擇後寫入變數 |
| branch  | 根據變數值跳轉到 thenGo / elseGo |
| goto   | 無條件跳轉 |
| effect | 觸發副作用（透過 `EffectHandler` 橋接到 App/遊戲） |
| end    | 結束流程 |

---

## 專案結構（重點）

### Demo 1：Tutorial / Interactive（單一 workflow）
- `core/src/main/java/.../TaskFlowTutorial.java`  
  最小教學版（log + setVar + branch + end）
- `core/src/main/java/.../TaskFlowInteractiveExample.java`  
  互動版（加入 choice + goto + Presenter）
- `core/src/main/resources/xml/task2.xml`  
  五關互動示範流程（中文劇情）

### Demo 2：Effect Bridge Demo（A→B 串接 + 共享狀態）
- `core/src/main/java/.../TaskFlowEffectBridgeDemo.java`  
  橋接版（加入 effect + 共享 VariableStore）
- `core/src/main/resources/xml/taskA.xml`  
  任務 A：完成後寫入 `questA_done` / `gold` / 旗標
- `core/src/main/resources/xml/taskB.xml`  
  任務 B：依賴 A 的結果（例如 `questA_done=true` 才能進行）
- `core/src/main/resources/workflow.xsd`  
  DSL 的 schema 驗證（包含 effect 所需欄位）

---

## 如何執行

### 方式 1：Gradle 執行 main（範例）
- `:core:TaskFlowInteractiveExample.main()`  
  跑「五關互動示範」
- `:core:TaskFlowEffectBridgeDemo.main()`  
  跑「任務 A → 任務 B 串接」並展示共享變數

### 方式 2：IDE / AS 直接 Run main
直接執行對應的 `main()` 即可。

---

## DSL 設計說明（核心概念）

### 1) Workflow 是「task 清單」
- 預設從第一個 `<task>` 開始跑
- `branch/goto/end` 會改變流程走向
- 其他任務（log/setVar/choice/effect）預設走「下一個 task」

### 2) 分支後務必「匯合」
流程預設順序向下執行，  
所以分支路徑結尾常需要 `goto` 回到共同節點（join point），避免路徑串線。

### 3) Effect Bridge：流程「決定」與 App「執行」分離
- DSL/Workflow 只負責描述「何時觸發什麼效果」（effectName + parameters）
- App/遊戲端負責真正執行（例如加金幣、解鎖關卡、更新 GameControl 狀態）
- 兩者透過 `EffectHandler` 介面接起來，Console demo 只是其中一種實作

---

## Presenter 抽象（未來接 UI 的關鍵）

互動選擇並沒有綁死 Console，而是透過：

- `ChoicePresenter.showTwoOptions(...)`

Console 版本只是其中一個實作：

- `ConsoleChoicePresenter`

你可以替換成：
- LibGDX Dialog / Scene2D Button
- Android AlertDialog
- Web UI（如果未來要上雲）

---

## 下一步可擴充方向（建議）

- **Loader 健全性檢查**：驗證 `goto/branch` 的目標 taskId 是否存在
- **多選項 choice**：支援 3+ 選項、動態選項清單
- **條件運算**：branch 支援 `> < contains` 等（expression）
- **型別系統**：variables 支援 boolean/int/string
- **非同步等待**：choice 回傳 WAIT，等 UI callback 再 resume
- **持久化 VariableStore**：讓任務 A 跑完關閉程式後，任務 B 仍可讀到 A 的結果（File/DB/JsonCrudCore）
