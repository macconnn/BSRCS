-- =====================================================================
-- 線上棒球比賽紀錄表 — PostgreSQL DDL（對照 JPA Entity）
-- prod 預設 spring.jpa.hibernate.ddl-auto=update 會自動建表；
-- 若要改成 validate，請先以本檔手動建立資料庫結構。
-- =====================================================================

-- ------------------------------------------------ 帳號 / 驗證
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(120) NOT NULL,
    display_name  VARCHAR(60),
    role          VARCHAR(16)  NOT NULL DEFAULT 'EDITOR',   -- VIEWER / EDITOR / ADMIN
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMP,
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

-- OTP 驗證碼（JavaMail 寄送）
CREATE TABLE otp_code (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(120) NOT NULL,
    code          VARCHAR(10)  NOT NULL,
    purpose       VARCHAR(16)  NOT NULL DEFAULT 'LOGIN',
    expires_at    TIMESTAMP    NOT NULL,
    consumed_at   TIMESTAMP,
    attempt_count INT          NOT NULL DEFAULT 0,
    request_ip    VARCHAR(45),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_otp_email   ON otp_code (email);
CREATE INDEX idx_otp_expires ON otp_code (expires_at);

-- 登入 token（存在 cookie BB_TOKEN）
CREATE TABLE auth_token (
    id         BIGSERIAL PRIMARY KEY,
    token      VARCHAR(64) NOT NULL,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id),
    issued_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP   NOT NULL,
    revoked_at TIMESTAMP,
    user_agent VARCHAR(300)
);
CREATE UNIQUE INDEX idx_auth_token_token ON auth_token (token);

-- ------------------------------------------------ 球隊 / 球員
CREATE TABLE team (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(60) NOT NULL,
    short_name    VARCHAR(20),
    color_hex     VARCHAR(10),
    owner_user_id BIGINT,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- 注意：這裡「沒有」batting_avg 欄位。打擊率不再是寫死存在 player 表上的值，
-- 而是由 at_bat（下方，所有打擊紀錄表）依每個打席的實際結果動態加總計算出來，
-- 新球員一律視同 0 打數 0 安打（顯示 .000），紀錄員每記一次打席就會即時反映最新打擊率。
CREATE TABLE player (
    id               BIGSERIAL PRIMARY KEY,
    team_id          BIGINT      NOT NULL REFERENCES team (id),
    name             VARCHAR(60) NOT NULL,
    jersey_number    VARCHAR(5),
    default_position VARCHAR(20),
    active           BOOLEAN     NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_player_team ON player (team_id);

-- 若是從舊版（有 batting_avg 欄位）升級，執行下面這行即可移除寫死欄位：
-- ALTER TABLE player DROP COLUMN IF EXISTS batting_avg;

-- ------------------------------------------------ 比賽
CREATE TABLE game (
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR(100) NOT NULL,
    game_date              DATE,
    venue                  VARCHAR(100),
    remark                 VARCHAR(500),
    away_team_id           BIGINT       NOT NULL REFERENCES team (id),   -- 先攻（上半局進攻）
    home_team_id           BIGINT       NOT NULL REFERENCES team (id),   -- 後攻（下半局進攻）
    status                 VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED',    -- SCHEDULED / LIVE / FINISHED
    total_innings          INT          NOT NULL DEFAULT 9,
    inning                 INT          NOT NULL DEFAULT 1,
    inning_half            VARCHAR(8)   NOT NULL DEFAULT 'TOP',          -- TOP / BOTTOM
    outs                   INT          NOT NULL DEFAULT 0,
    balls                  INT          NOT NULL DEFAULT 0,
    strikes                INT          NOT NULL DEFAULT 0,
    runner_first           BIGINT,                                       -- game_lineup.id
    runner_second          BIGINT,
    runner_third           BIGINT,
    away_score             INT          NOT NULL DEFAULT 0,
    home_score             INT          NOT NULL DEFAULT 0,
    away_hits              INT          NOT NULL DEFAULT 0,
    home_hits              INT          NOT NULL DEFAULT 0,
    away_errors            INT          NOT NULL DEFAULT 0,
    home_errors            INT          NOT NULL DEFAULT 0,
    away_batter_index      INT          NOT NULL DEFAULT 0,
    home_batter_index      INT          NOT NULL DEFAULT 0,
    away_pitcher_lineup_id BIGINT,
    home_pitcher_lineup_id BIGINT,
    action_seq             BIGINT       NOT NULL DEFAULT 0,
    created_by             BIGINT,
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_game_status ON game (status);

-- 該場打線（棒次 + 守備位置 + 本場個人成績）
CREATE TABLE game_lineup (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT     NOT NULL REFERENCES game (id),
    team_id       BIGINT     NOT NULL REFERENCES team (id),
    player_id     BIGINT     NOT NULL REFERENCES player (id),
    team_side     VARCHAR(8) NOT NULL,                    -- AWAY / HOME
    batting_order INT        NOT NULL,                    -- 1~9
    position      VARCHAR(20),
    is_starter    BOOLEAN    NOT NULL DEFAULT TRUE,
    active        BOOLEAN    NOT NULL DEFAULT TRUE,
    at_bats       INT        NOT NULL DEFAULT 0,
    hits          INT        NOT NULL DEFAULT 0,
    rbi           INT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_lineup_game      ON game_lineup (game_id);
CREATE INDEX idx_lineup_game_side ON game_lineup (game_id, team_side);
-- 供「動態計算生涯打擊率」查詢使用：從 at_bat 經 game_lineup 找出某位球員所有打席
CREATE INDEX idx_lineup_player    ON game_lineup (player_id);

-- 每局得分（記分板）
CREATE TABLE inning_score (
    id        BIGSERIAL PRIMARY KEY,
    game_id   BIGINT     NOT NULL REFERENCES game (id),
    team_side VARCHAR(8) NOT NULL,
    inning    INT        NOT NULL,
    runs      INT        NOT NULL DEFAULT 0,
    hits      INT        NOT NULL DEFAULT 0,
    errors    INT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_inning_score UNIQUE (game_id, team_side, inning)
);

-- 打席紀錄
CREATE TABLE at_bat (
    id                BIGSERIAL PRIMARY KEY,
    game_id           BIGINT     NOT NULL REFERENCES game (id),
    seq_no            INT        NOT NULL,
    inning            INT        NOT NULL,
    inning_half       VARCHAR(8) NOT NULL,
    batting_side      VARCHAR(8) NOT NULL,
    batter_lineup_id  BIGINT     NOT NULL REFERENCES game_lineup (id),
    pitcher_lineup_id BIGINT,
    result            VARCHAR(32),                        -- PlayResult enum
    rbi               INT        NOT NULL DEFAULT 0,
    outs_recorded     INT        NOT NULL DEFAULT 0,
    runs_scored       INT        NOT NULL DEFAULT 0,
    description       VARCHAR(300),
    action_seq        BIGINT     NOT NULL DEFAULT 0,
    finished          BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_atbat_game ON at_bat (game_id, seq_no);
CREATE INDEX idx_atbat_batter_lineup ON at_bat (batter_lineup_id);

-- 投球紀錄
CREATE TABLE pitch (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT      NOT NULL REFERENCES game (id),
    at_bat_id     BIGINT      NOT NULL REFERENCES at_bat (id),
    seq_no        INT         NOT NULL,
    call_type     VARCHAR(16) NOT NULL,                   -- STRIKE / BALL / FOUL
    pitch_type    VARCHAR(20),                            -- 直球 / 曲球 / 滑球 / 變速球
    speed_kmh     INT,
    balls_after   INT         NOT NULL DEFAULT 0,
    strikes_after INT         NOT NULL DEFAULT 0,
    action_seq    BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pitch_atbat ON pitch (at_bat_id);

-- 近期賽況 feed
CREATE TABLE game_event (
    id          BIGSERIAL PRIMARY KEY,
    game_id     BIGINT       NOT NULL REFERENCES game (id),
    inning      INT          NOT NULL,
    inning_half VARCHAR(8)   NOT NULL,
    event_type  VARCHAR(20)  NOT NULL,                    -- PITCH / RESULT / INNING / SYSTEM
    player_name VARCHAR(60),
    description VARCHAR(300) NOT NULL,
    color_tag   VARCHAR(12),
    action_seq  BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_event_game ON game_event (game_id, id);

-- 還原點（上一打席 / 復原）
CREATE TABLE game_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    game_id     BIGINT    NOT NULL REFERENCES game (id),
    action_seq  BIGINT    NOT NULL,
    action_name VARCHAR(40),
    state_json  TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_snapshot_game ON game_snapshot (game_id, action_seq);

-- 協同記錄員
CREATE TABLE game_editor (
    id         BIGSERIAL PRIMARY KEY,
    game_id    BIGINT    NOT NULL REFERENCES game (id),
    user_id    BIGINT    NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_game_editor UNIQUE (game_id, user_id)
);
