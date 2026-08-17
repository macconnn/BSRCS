# 線上棒球比賽紀錄表（Baseball Score）

Java 17 + Spring Boot 3 單一 jar 專案：後端 API、四張前端畫面（Thymeleaf 樣板）、OTP 登入（JavaMail）全部包在同一個服務裡，**不需要額外的前端專案**。

- 開發：H2（記憶體）
- 佈署：PostgreSQL（Docker Compose）
- 權限：**不使用 Spring Security**，以 `AuthInterceptor` + `@RequireEditor` 判斷編輯者 / 瀏覽者

---

## 1. 快速開始

```bash
# 開發模式（H2 + 驗證碼直接印在 console）
mvn spring-boot:run

# 打包
mvn clean package            # target/baseball-score.jar
java -jar target/baseball-score.jar

# Docker（app + PostgreSQL）
docker compose up --build    # http://localhost:8080
```

啟動後：

| 路徑 | 說明 |
| --- | --- |
| `/` | 登入頁（可選擇「以瀏覽者身份進入」） |
| `/games` | 比賽列表 / 新增比賽 |
| `/teams` | 球隊管理（球隊列表 / 新增球隊） |
| `/teams/{id}` | 球員名單：背號、姓名、守備位置、打擊率（編輯者可增修，瀏覽者唯讀） |
| `/games/{id}` | 比賽紀錄畫面，**自動判斷 PC / Mobile、編輯 / 檢視** |
| `/h2-console` | 開發模式的 H2 主控台（JDBC URL：`jdbc:h2:mem:baseball`） |

開發模式不需要 SMTP：`app.mail.enabled=false` 時驗證碼會以 `[DEV MAIL] ... 驗證碼 = 123456` 印在 console，任何 Email 皆可登入並自動建立編輯者帳號。

---

## 2. 四張畫面怎麼被選出來

`PageController` 依「登入狀態 × 裝置」決定樣板：

| | PC | Mobile |
| --- | --- | --- |
| 已登入（EDITOR / ADMIN） | `editor-pc.html` | `editor-mobile.html` |
| 未登入（VIEWER） | `viewer-pc.html` | `viewer-mobile.html` |

裝置判斷在 `DeviceUtil.detect()`，讀 `User-Agent`（平板視為 PC）。測試時可用網址參數覆寫：

- `/games/1?device=mobile` — 強制手機版面
- `/games/1?mode=viewer` — 編輯者切換到檢視模式

四張畫面共用 `/css/app.css` 與 `/js/app.js`，資料一律來自 `GET /api/games/{id}/state`。檢視模式每 10 秒輪詢，編輯模式在每次操作後即時更新、另外每 30 秒同步一次。

---

## 3. 登入與權限（不使用 Spring Security）

```
POST /api/auth/otp     { email }              → 產生 6 碼驗證碼寫入 otp_code，JavaMail 寄出
POST /api/auth/verify  { email, code }        → 建立 app_user（若不存在）＋ auth_token，寫入 HttpOnly cookie BB_TOKEN
POST /api/auth/logout                         → 標記 token revoked、清掉 cookie
GET  /api/auth/me                             → { loggedIn, role, canEdit }
```

- `AuthInterceptor` 對每個 request 解析 cookie，放進 `request.currentUser`；沒有 token 就是 `VIEWER`。
- 需要編輯權限的 API 標 `@RequireEditor`，攔截器直接回 **403** 與中文訊息。
- 驗證碼規則寫在 `application.yml` 的 `app.otp`：長度 6、10 分鐘失效、60 秒才能重寄、最多錯 5 次。

正式環境的 SMTP 由環境變數帶入（見 `docker-compose.yml`）：`MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_PASSWORD / MAIL_FROM`。Gmail 需使用「應用程式密碼」。

---

## 4. 資料表設計

| 資料表 | 用途 |
| --- | --- |
| `app_user` | 編輯者帳號（瀏覽者不需帳號） |
| `otp_code` | **OTP 驗證碼**：code、purpose、expires_at、consumed_at、attempt_count、request_ip |
| `auth_token` | 登入 token（cookie）、到期與註銷時間 |
| `team` / `player` | 球隊、球員（背號、守備位置、賽前打擊率） |
| `game` | 比賽基本資料 **＋ 即時狀態**（局數、上下半局、好壞球、出局、壘包跑者、比分、H/E、目前棒次、投手、action_seq） |
| `game_lineup` | 該場打線：棒次、守備位置、本場打數 / 安打 / 打點 |
| `inning_score` | 每局得分（記分板 1~9 局 + R/H/E） |
| `at_bat` | 打席紀錄：打者、投手、結果、打點、出局數 |
| `pitch` | 投球紀錄：好壞球判定、球種、球速、當下球數 |
| `game_event` | 近期賽況 feed（含前端小圓點顏色） |
| `game_snapshot` | 每個動作前的狀態快照，支援「上一打席 / 復原」 |
| `game_editor` | 協同記錄員（誰可以編輯這場比賽） |

完整 DDL：`src/main/resources/db/schema_postgres.sql`。開發用 H2 由 `ddl-auto=create-drop` 自動建立。

**壘包狀態**存在 `game.runner_first / runner_second / runner_third`，值是 `game_lineup.id`，所以可以回推壘上是誰。

---

## 4.5 球隊 / 球員管理 API

```
GET    /api/teams                    # 球隊列表（含球員人數）— 瀏覽者也可讀
GET    /api/teams/{id}               # 球隊 + 球員名單
POST   /api/teams                    @RequireEditor  { name, shortName, colorHex }
PUT    /api/teams/{id}               @RequireEditor
POST   /api/teams/{id}/players       @RequireEditor  { name, jerseyNumber, defaultPosition, battingAvg }
PUT    /api/players/{playerId}       @RequireEditor
DELETE /api/players/{playerId}       @RequireEditor  # 軟刪除（active=false），保留歷史紀錄
```

畫面 `/teams`、`/teams/{id}` 對瀏覽者只渲染表格，不輸出任何新增 / 編輯按鈕；即使自行呼叫 API 也會被 `@RequireEditor` 擋下回 403。

---

## 5. 比賽 API

讀取（瀏覽者也可以）：

```
GET /api/games?filter=live|past|all
GET /api/games/{id}/state          # 前端畫面的唯一資料來源
GET /api/games/{id}/lineups/{lineupId}/log   # 球員本場表現（每個打席的逐球與結果）
GET /api/games/teams
```

編輯（`@RequireEditor`，未登入回 403）：

```
POST /api/games                      { name, gameDate, venue, awayTeamId, homeTeamId }
POST /api/games/{id}/pitches         { call: STRIKE|BALL|FOUL, pitchType, speedKmh }
POST /api/games/{id}/results         { result: SINGLE|DOUBLE|...|OTHER }
POST /api/games/{id}/next-batter
POST /api/games/{id}/undo            # 上一打席（還原到上一個快照）
POST /api/games/{id}/reset           # 重新開始
POST /api/games/{id}/start
POST /api/games/{id}/finish          # 結束比賽
```

所有回應格式一致：`{ "success": true, "message": "...", "data": { ... } }`。

### 記錄邏輯（`ScoringService`）

- 好球 3 個自動判三振、壞球 4 個自動判保送，界外球在兩好球後不再增加好球數。
- `PlayResult` enum 內建每種結果的「上到幾壘 / 製造幾出局 / 是否計安打 / 跑者推進幾個壘包」，保送採用擠壘規則。
- 三出局自動換局、清空壘包與球數；打完第 9 局下自動結束比賽。
- 每個動作前寫入 `game_snapshot`，`undo` 會還原比分、壘包、每局得分與個人成績，並刪除該動作之後產生的 `pitch` / `at_bat` / `game_event`。

### 球員本場表現

四張比賽畫面的打線中，球員姓名皆可點擊，會開啟 modal 顯示該球員本場：

- 打數-安打、打點、保送、三振統計
- 每個打席的局數與結果（安打 / 三振 / 保送… 以顏色區分）
- 每個打席的逐球紀錄：好球 (S) / 壞球 (B) / 界外 (F) 圓點序列，以及球種、球速與當下球數

資料來源 `GET /api/games/{id}/lineups/{lineupId}/log`，由 `at_bat` + `pitch` 兩張表組出。

---

## 6. 設定總覽

| 設定 | 預設 | 說明 |
| --- | --- | --- |
| `app.otp.ttl-minutes` | 10 | 驗證碼有效時間 |
| `app.otp.resend-interval-seconds` | 60 | 重寄冷卻 |
| `app.otp.max-attempts` | 5 | 驗證碼錯誤上限 |
| `app.auth.cookie-name` | `BB_TOKEN` | 登入 cookie 名稱 |
| `app.auth.token-ttl-days` | 7 | 登入有效天數 |
| `app.mail.enabled` | dev `false` / prod `true` | 關閉時驗證碼只印 log |
| `app.seed` | dev `true` | 啟動建立「藍隊 vs 紅隊」示範比賽 |

> 註：mockup 上的球數標籤是「好球 (B) / 壞球 (S)」，字母與中文對不起來，本專案統一改為 **好球 (S) / 壞球 (B)**（S = strike、B = ball）。若要完全照圖，改樣板的文字即可，後端欄位不受影響。

---

## 7. 專案結構

```
src/main/java/com/baseball/score/
├── config/       AppProperties、AuthInterceptor、RequireEditor、WebConfig、例外處理
├── controller/   PageController（四張畫面路由）、AuthApiController、GameApiController
├── dto/          請求 / 回應物件
├── entity/       12 張表對應的 JPA Entity
├── enums/        Role、GameStatus、InningHalf、PitchCall、PlayResult、DeviceType…
├── repository/   Spring Data JPA
└── service/      AuthService、MailService、ScoringService、GameQueryService、GameService、DataSeeder

src/main/resources/
├── templates/    login、games、editor-pc、viewer-pc、editor-mobile、viewer-mobile
├── static/       css/app.css、js/app.js
└── db/           schema_postgres.sql
```
