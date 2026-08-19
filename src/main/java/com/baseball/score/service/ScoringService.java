package com.baseball.score.service;

import com.baseball.score.entity.*;
import com.baseball.score.enums.*;
import com.baseball.score.repository.*;
import com.baseball.score.util.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 記錄邏輯核心：投球、打席結果、壘包推進、換局、復原。
 * 每次動作前會先寫一筆 game_snapshot，「上一打席」＝還原到上一個快照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringService {

    private final GameRepository gameRepo;
    private final GameLineupRepository lineupRepo;
    private final InningScoreRepository inningRepo;
    private final AtBatRepository atBatRepo;
    private final PitchRepository pitchRepo;
    private final GameEventRepository eventRepo;
    private final GameSnapshotRepository snapshotRepo;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------ 查詢輔助

    public Game getGame(Long gameId) {
        return gameRepo.findById(gameId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到比賽 id=" + gameId));
    }

    public TeamSide battingSide(Game game) {
        return game.getHalf() == InningHalf.TOP ? TeamSide.AWAY : TeamSide.HOME;
    }

    public TeamSide fieldingSide(Game game) {
        return battingSide(game) == TeamSide.AWAY ? TeamSide.HOME : TeamSide.AWAY;
    }

    public List<GameLineup> lineup(Game game, TeamSide side) {
        return lineupRepo.findByGameIdAndTeamSideAndActiveTrueOrderByBattingOrderAsc(game.getId(), side);
    }

    public GameLineup currentBatter(Game game) {
        List<GameLineup> list = lineup(game, battingSide(game));
        if (list.isEmpty()) throw new ApiException("此比賽尚未設定打線");
        int idx = batterIndex(game) % list.size();
        return list.get(idx);
    }

    public GameLineup nextBatter(Game game) {
        List<GameLineup> list = lineup(game, battingSide(game));
        if (list.isEmpty()) return null;
        return list.get((batterIndex(game) + 1) % list.size());
    }

    public GameLineup currentPitcher(Game game) {
        Long id = fieldingSide(game) == TeamSide.AWAY ? game.getAwayPitcherLineupId() : game.getHomePitcherLineupId();
        if (id != null) return lineupRepo.findById(id).orElse(null);
        return lineup(game, fieldingSide(game)).stream()
                .filter(l -> "投手".equals(l.getPosition())).findFirst().orElse(null);
    }

    private int batterIndex(Game game) {
        return battingSide(game) == TeamSide.AWAY ? game.getAwayBatterIndex() : game.getHomeBatterIndex();
    }

    private void setBatterIndex(Game game, int idx) {
        if (battingSide(game) == TeamSide.AWAY) game.setAwayBatterIndex(idx);
        else game.setHomeBatterIndex(idx);
    }

    // ------------------------------------------------------------ 打席

    @Transactional
    public AtBat currentOrCreateAtBat(Game game) {
        return atBatRepo.findFirstByGameIdAndFinishedFalseOrderBySeqNoDesc(game.getId())
                .orElseGet(() -> {
                    GameLineup batter = currentBatter(game);
                    GameLineup pitcher = currentPitcher(game);
                    int seq = atBatRepo.findFirstByGameIdOrderBySeqNoDesc(game.getId())
                            .map(AtBat::getSeqNo).orElse(0) + 1;
                    return atBatRepo.save(AtBat.builder()
                            .game(game).seqNo(seq)
                            .inning(game.getInning()).half(game.getHalf())
                            .battingSide(battingSide(game))
                            .batterLineup(batter)
                            .pitcherLineupId(pitcher == null ? null : pitcher.getId())
                            .actionSeq(game.getActionSeq())
                            .build());
                });
    }

    public List<Pitch> pitchesOfCurrentAtBat(Game game) {
        return atBatRepo.findFirstByGameIdAndFinishedFalseOrderBySeqNoDesc(game.getId())
                .map(ab -> pitchRepo.findByAtBatIdOrderBySeqNoDesc(ab.getId()))
                .orElse(List.of());
    }

    // ------------------------------------------------------------ 記錄動作

    /** 記錄一球（好球 / 壞球 / 界外球） */
    @Transactional
    public void recordPitch(Long gameId, PitchCall call, String pitchType, Integer speedKmh) {
        Game game = getGame(gameId);
        assertLive(game);
        snapshot(game, "PITCH_" + call.name());

        AtBat atBat = currentOrCreateAtBat(game);
        GameLineup batter = atBat.getBatterLineup();

        switch (call) {
            case STRIKE -> game.setStrikes(game.getStrikes() + 1);
            case BALL -> game.setBalls(game.getBalls() + 1);
            case FOUL -> { if (game.getStrikes() < 2) game.setStrikes(game.getStrikes() + 1); }
        }

        int seq = pitchRepo.findByAtBatIdOrderBySeqNoDesc(atBat.getId()).stream()
                .mapToInt(Pitch::getSeqNo).max().orElse(0) + 1;
        pitchRepo.save(Pitch.builder()
                .game(game).atBat(atBat).seqNo(seq).call(call)
                .pitchType(pitchType).speedKmh(speedKmh)
                .ballsAfter(game.getBalls()).strikesAfter(game.getStrikes())
                .actionSeq(game.getActionSeq())
                .build());

        addEvent(game, "PITCH", batter.getPlayer().getName(), call.getLabel(),
                switch (call) { case STRIKE -> "green"; case BALL -> "yellow"; case FOUL -> "blue"; });

        if (game.getStrikes() >= 3) {
            applyResult(game, atBat, PlayResult.STRIKEOUT);
        } else if (game.getBalls() >= 4) {
            applyResult(game, atBat, PlayResult.WALK);
        }
        touch(game);
    }

    /** 記錄打席結果（安打、四壞球保送、雙殺打…） */
    @Transactional
    public void recordResult(Long gameId, PlayResult result) {
        Game game = getGame(gameId);
        assertLive(game);
        snapshot(game, "RESULT_" + result.name());
        AtBat atBat = currentOrCreateAtBat(game);
        applyResult(game, atBat, result);
        touch(game);
    }

    /** 下一打席（沒有結果就跳過，例如換人） */
    @Transactional
    public void nextBatter(Long gameId) {
        Game game = getGame(gameId);
        assertLive(game);
        snapshot(game, "NEXT_BATTER");
        AtBat atBat = atBatRepo.findFirstByGameIdAndFinishedFalseOrderBySeqNoDesc(game.getId()).orElse(null);
        if (atBat != null) {
            atBat.setFinished(true);
            if (atBat.getResult() == null) atBat.setResult(PlayResult.OTHER);
            atBatRepo.save(atBat);
        }
        rotateBatter(game);
        resetCount(game);
        touch(game);
    }

    /**
     * 盜壘：獨立於打席結果之外的跑者事件（發生在打席進行中，不影響打者的打數／不結束打席）。
     * outcome=SAFE：跑者從 fromBase 移動到 toBase（toBase=4 代表跑回本壘得分）；
     *   error=true 代表這次推進中有一段是因為守備失誤多跑出來的，會計入球隊失誤數，
     *   但不論推進幾個壘包，都只算球員一次盜壘成功。
     * outcome=CAUGHT：跑者出局，+1 出局數，toBase／error 會被忽略。
     */
    @Transactional
    public void recordSteal(Long gameId, Integer fromBase, Integer toBase, StealOutcome outcome, boolean error) {
        Game game = getGame(gameId);
        assertLive(game);
        if (fromBase == null || fromBase < 1 || fromBase > 3) {
            throw new ApiException("出發壘包錯誤");
        }
        snapshot(game, "STEAL_" + outcome);

        Long[] bases = { game.getRunnerFirst(), game.getRunnerSecond(), game.getRunnerThird() };
        int idx = fromBase - 1;
        Long runnerId = bases[idx];
        if (runnerId == null) {
            throw new ApiException(baseLabel(fromBase) + " 目前沒有跑者，無法盜壘");
        }
        GameLineup runner = lineupRepo.findById(runnerId)
                .orElseThrow(() -> new ApiException("找不到跑者資料"));
        TeamSide batting = battingSide(game);
        String runnerName = runner.getPlayer().getName();

        if (outcome == StealOutcome.CAUGHT) {
            bases[idx] = null;
            game.setOuts(game.getOuts() + 1);
            runner.setCaughtStealing(runner.getCaughtStealing() + 1);
            lineupRepo.save(runner);
            addEvent(game, "STEAL", runnerName, runnerName + " 盜" + baseLabel(fromBase + 1) + "失敗，被阻殺出局", "red");
        } else {
            if (toBase == null || toBase <= fromBase || toBase > 4) {
                throw new ApiException("目標壘包錯誤");
            }
            if (toBase <= 3 && bases[toBase - 1] != null) {
                throw new ApiException(baseLabel(toBase) + " 已經有其他跑者，無法盜壘上去");
            }

            bases[idx] = null;
            int runs = 0;
            if (toBase == 4) {
                runs = 1;
            } else {
                bases[toBase - 1] = runnerId;
            }

            runner.setStolenBases(runner.getStolenBases() + 1);
            lineupRepo.save(runner);
            if (error) addError(game, fieldingSide(game));
            if (runs > 0) addRuns(game, batting, runs);

            String desc = runnerName + " 從" + baseLabel(fromBase) + "盜上"
                    + (toBase == 4 ? "本壘，得 1 分" : baseLabel(toBase))
                    + (error ? "（含守備失誤）" : "");
            addEvent(game, "STEAL", runnerName, desc, "blue");
        }

        game.setRunnerFirst(bases[0]);
        game.setRunnerSecond(bases[1]);
        game.setRunnerThird(bases[2]);

        if (game.getOuts() >= 3) changeHalfInning(game);
        touch(game);
    }

    private String baseLabel(int base) {
        return switch (base) {
            case 1 -> "一壘";
            case 2 -> "二壘";
            case 3 -> "三壘";
            default -> "本壘";
        };
    }

    /**
     * 加碼失誤推進：安打／出局的打席結果已經照正常規則推進壘包之後，
     * 因為守備失誤讓某位跑者又多推進了一個以上壘包（例如二壘安打，外野傳球失誤讓跑者從二壘多跑上三壘；
     * 或一壘安打，傳球失誤讓打者從一壘多跑上二壘）。
     * 只會多算一次球隊失誤、視情況加分；不計安打、不計打者打點（因守備失誤多跑回來的分不算打點，
     * 這點跟安打本身的打點是分開計算的，安打的打點已經在 applyResult() 那次就算完了）。
     */
    @Transactional
    public void recordErrorAdvance(Long gameId, Integer fromBase, Integer toBase) {
        Game game = getGame(gameId);
        assertLive(game);
        if (fromBase == null || fromBase < 1 || fromBase > 3) {
            throw new ApiException("出發壘包錯誤");
        }
        if (toBase == null || toBase <= fromBase || toBase > 4) {
            throw new ApiException("目標壘包錯誤");
        }
        snapshot(game, "ERROR_ADVANCE");

        Long[] bases = { game.getRunnerFirst(), game.getRunnerSecond(), game.getRunnerThird() };
        int idx = fromBase - 1;
        Long runnerId = bases[idx];
        if (runnerId == null) {
            throw new ApiException(baseLabel(fromBase) + " 目前沒有跑者，無法記錄失誤推進");
        }
        if (toBase <= 3 && bases[toBase - 1] != null) {
            throw new ApiException(baseLabel(toBase) + " 已經有其他跑者，無法推進上去");
        }

        GameLineup runner = lineupRepo.findById(runnerId)
                .orElseThrow(() -> new ApiException("找不到跑者資料"));
        TeamSide batting = battingSide(game);
        String runnerName = runner.getPlayer().getName();

        bases[idx] = null;
        int runs = 0;
        if (toBase == 4) {
            runs = 1;
        } else {
            bases[toBase - 1] = runnerId;
        }

        addError(game, fieldingSide(game));
        if (runs > 0) addRuns(game, batting, runs);

        game.setRunnerFirst(bases[0]);
        game.setRunnerSecond(bases[1]);
        game.setRunnerThird(bases[2]);

        String desc = runnerName + " 因守備失誤，從" + baseLabel(fromBase) + "多推進到"
                + (toBase == 4 ? "本壘，得 1 分（不計打點）" : baseLabel(toBase));
        addEvent(game, "ERROR_ADVANCE", runnerName, desc, "red");

        touch(game);
    }

    /**
     * 手動編輯壘包：用於不可預期的特殊狀況（現有規則無法涵蓋時），
     * 讓記錄員直接指定三個壘包各是哪位球員（必須是目前進攻方、仍在場上的球員），null 代表無人。
     * 這個動作會整組覆蓋壘包狀態，不影響出局數、球數、比分、安打／失誤數。
     */
    @Transactional
    public void editBases(Long gameId, Long firstId, Long secondId, Long thirdId) {
        Game game = getGame(gameId);
        assertLive(game);
        snapshot(game, "EDIT_BASES");

        TeamSide batting = battingSide(game);
        List<Long> seen = new java.util.ArrayList<>();
        for (Long id : java.util.Arrays.asList(firstId, secondId, thirdId)) {
            if (id == null) continue;
            if (seen.contains(id)) {
                throw new ApiException("同一位球員不能同時站在兩個壘包");
            }
            seen.add(id);
            GameLineup l = lineupRepo.findById(id)
                    .orElseThrow(() -> new ApiException("找不到球員資料 id=" + id));
            if (!l.getGame().getId().equals(gameId)) throw new ApiException("這位球員不屬於本場比賽");
            if (l.getTeamSide() != batting) throw new ApiException(l.getPlayer().getName() + " 不是目前進攻方的球員，無法設為跑者");
            if (!Boolean.TRUE.equals(l.getActive())) throw new ApiException(l.getPlayer().getName() + " 目前不在場上，無法設為跑者");
        }

        game.setRunnerFirst(firstId);
        game.setRunnerSecond(secondId);
        game.setRunnerThird(thirdId);

        addEvent(game, "SYSTEM", null, "手動調整壘包狀態", "gray");
        touch(game);
    }

    /**
     * 手動編輯「某一局」的得分：用來修正手誤造成的比分錯誤。
     * 更新該局分數後，會直接把該隊的總分重新計算成「該隊每一局分數的加總」，
     * 所以總分永遠跟每局分數對得起來，不會有兜不攏的情況。
     */
    @Transactional
    public void editInningScore(Long gameId, TeamSide side, Integer inning, Integer runs) {
        Game game = getGame(gameId);
        assertLive(game);
        if (inning == null || inning < 1) {
            throw new ApiException("局數錯誤");
        }
        if (runs == null || runs < 0) {
            throw new ApiException("分數必須是不小於 0 的整數");
        }
        snapshot(game, "EDIT_INNING_SCORE");

        InningScore is = inningScore(game, side, inning);
        is.setRuns(runs);
        inningRepo.save(is);

        // 重新計算該隊總分＝該隊每一局分數的加總，確保總分跟每局分數永遠一致。
        int total = inningRepo.findByGameIdOrderByInningAsc(game.getId()).stream()
                .filter(i -> i.getTeamSide() == side)
                .mapToInt(InningScore::getRuns)
                .sum();
        if (side == TeamSide.AWAY) game.setAwayScore(total);
        else game.setHomeScore(total);

        String teamLabel = side == TeamSide.AWAY ? "客隊" : "主隊";
        addEvent(game, "SYSTEM", null,
                "手動調整" + teamLabel + "第 " + inning + " 局得分為 " + runs + " 分（總分同步更新為 " + total + " 分）", "gray");
        touch(game);
    }

    /** 上一打席 / 復原：還原到上一個快照 */
    @Transactional
    public void undo(Long gameId) {
        Game game = getGame(gameId);
        GameSnapshot snap = snapshotRepo.findFirstByGameIdOrderByActionSeqDesc(gameId)
                .orElseThrow(() -> new ApiException("已經是最初狀態，無法復原"));
        restore(game, snap);
        snapshotRepo.delete(snap);
    }

    /** 重新開始（清空所有紀錄） */
    @Transactional
    public void reset(Long gameId) {
        Game game = getGame(gameId);
        pitchRepo.deleteByGameId(gameId);
        atBatRepo.deleteByGameId(gameId);
        eventRepo.deleteByGameId(gameId);
        snapshotRepo.deleteByGameId(gameId);
        inningRepo.deleteByGameId(gameId);

        game.setInning(1); game.setHalf(InningHalf.TOP);
        game.setOuts(0); game.setBalls(0); game.setStrikes(0);
        game.setRunnerFirst(null); game.setRunnerSecond(null); game.setRunnerThird(null);
        game.setAwayScore(0); game.setHomeScore(0);
        game.setAwayHits(0); game.setHomeHits(0);
        game.setAwayErrors(0); game.setHomeErrors(0);
        game.setAwayBatterIndex(0); game.setHomeBatterIndex(0);
        game.setActionSeq(0L);
        game.setStatus(GameStatus.LIVE);
        lineupRepo.findByGameIdOrderByTeamSideAscBattingOrderAsc(gameId).forEach(l -> {
            l.setAtBats(0); l.setHits(0); l.setRbi(0);
            lineupRepo.save(l);
        });
        addEvent(game, "SYSTEM", null, "比賽已重新開始", "gray");
        touch(game);
    }

    @Transactional
    public void finish(Long gameId) {
        Game game = getGame(gameId);
        game.setStatus(GameStatus.FINISHED);
        addEvent(game, "SYSTEM", null, "比賽結束", "gray");
        touch(game);
    }

    @Transactional
    public void start(Long gameId) {
        Game game = getGame(gameId);
        if (game.getStatus() == GameStatus.SCHEDULED) {
            game.setStatus(GameStatus.LIVE);
            addEvent(game, "SYSTEM", null, "比賽開始", "gray");
            touch(game);
        }
    }

    // ------------------------------------------------------------ 核心規則

    private void applyResult(Game game, AtBat atBat, PlayResult result) {
        TeamSide batting = battingSide(game);
        GameLineup batter = atBat.getBatterLineup();

        Long[] bases = { game.getRunnerFirst(), game.getRunnerSecond(), game.getRunnerThird() };
        int runs = 0;

        if (result == PlayResult.HOME_RUN) {
            for (int i = 0; i < 3; i++) if (bases[i] != null) { runs++; bases[i] = null; }
            runs++; // 打者本人
        } else if (result == PlayResult.WALK || result == PlayResult.HIT_BY_PITCH) {
            // 保送：只推進被擠壓的跑者
            if (bases[0] != null) {
                if (bases[1] != null) {
                    if (bases[2] != null) { runs++; }
                    bases[2] = bases[1];
                }
                bases[1] = bases[0];
            }
            bases[0] = batter.getId();
        } else {
            int adv = result.getRunnerAdvance();
            if (adv > 0) {
                for (int i = 2; i >= 0; i--) {
                    if (bases[i] == null) continue;
                    int target = i + adv;               // 0=一壘,1=二壘,2=三壘
                    if (target >= 3) { runs++; bases[i] = null; }
                    else { bases[target] = bases[i]; bases[i] = null; }
                }
            }
            if (result == PlayResult.DOUBLE_PLAY && bases[0] != null) bases[0] = null;
            if (result == PlayResult.CAUGHT_STEALING) {
                for (int i = 2; i >= 0; i--) if (bases[i] != null) { bases[i] = null; break; }
            }
            int b = result.getBases();
            if (b >= 1 && b <= 3) bases[b - 1] = batter.getId();
        }

        game.setRunnerFirst(bases[0]);
        game.setRunnerSecond(bases[1]);
        game.setRunnerThird(bases[2]);

        // 分數 / 安打 / 失誤
        if (runs > 0) addRuns(game, batting, runs);
        if (result.isHit()) addHit(game, batting);
        if (result.isError()) addError(game, fieldingSide(game));
        if (result.getOuts() > 0) game.setOuts(game.getOuts() + result.getOuts());

        // 個人成績（本場打線上的 0-1 顯示；生涯累積打擊率改由 AtBat 紀錄動態計算，見 PlayerStatsService）
        boolean countAtBat = result.isCountsAsAtBat();
        if (countAtBat) batter.setAtBats(batter.getAtBats() + 1);
        if (result.isHit()) batter.setHits(batter.getHits() + 1);
        batter.setRbi(batter.getRbi() + runs);
        lineupRepo.save(batter);

        atBat.setResult(result);
        atBat.setRbi(runs);
        atBat.setRunsScored(runs);
        atBat.setOutsRecorded(result.getOuts());
        atBat.setFinished(true);
        atBat.setDescription(batter.getPlayer().getName() + " " + result.getLabel()
                + (runs > 0 ? "（帶有 " + runs + " 分打點）" : ""));
        atBatRepo.save(atBat);

        addEvent(game, "RESULT", batter.getPlayer().getName(),
                result.getLabel() + (runs > 0 ? "，得 " + runs + " 分" : ""), colorOf(result));

        rotateBatter(game);
        resetCount(game);

        if (game.getOuts() >= 3) changeHalfInning(game);
    }

    private String colorOf(PlayResult r) {
        if (r.isHit() || r == PlayResult.HOME_RUN) return "blue";
        if (r.getOuts() > 0) return "red";
        if (r == PlayResult.WALK || r == PlayResult.HIT_BY_PITCH) return "yellow";
        return "gray";
    }

    private void rotateBatter(Game game) {
        List<GameLineup> list = lineup(game, battingSide(game));
        if (list.isEmpty()) return;
        setBatterIndex(game, (batterIndex(game) + 1) % list.size());
    }

    private void resetCount(Game game) {
        game.setBalls(0);
        game.setStrikes(0);
    }

    private void changeHalfInning(Game game) {
        game.setOuts(0);
        resetCount(game);
        game.setRunnerFirst(null);
        game.setRunnerSecond(null);
        game.setRunnerThird(null);

        if (game.getHalf() == InningHalf.TOP) {
            game.setHalf(InningHalf.BOTTOM);
        } else {
            game.setHalf(InningHalf.TOP);
            game.setInning(game.getInning() + 1);
        }
        addEvent(game, "INNING", null, "換局：" + game.getInning() + "局" + game.getHalf().getLabel(), "gray");

        if (game.getInning() > game.getTotalInnings()) {
            game.setStatus(GameStatus.FINISHED);
            addEvent(game, "SYSTEM", null, "比賽結束", "gray");
        }
    }

    private void addRuns(Game game, TeamSide side, int runs) {
        if (side == TeamSide.AWAY) game.setAwayScore(game.getAwayScore() + runs);
        else game.setHomeScore(game.getHomeScore() + runs);
        InningScore is = inningScore(game, side, game.getInning());
        is.setRuns(is.getRuns() + runs);
        inningRepo.save(is);
    }

    private void addHit(Game game, TeamSide side) {
        if (side == TeamSide.AWAY) game.setAwayHits(game.getAwayHits() + 1);
        else game.setHomeHits(game.getHomeHits() + 1);
        InningScore is = inningScore(game, side, game.getInning());
        is.setHits(is.getHits() + 1);
        inningRepo.save(is);
    }

    private void addError(Game game, TeamSide side) {
        if (side == TeamSide.AWAY) game.setAwayErrors(game.getAwayErrors() + 1);
        else game.setHomeErrors(game.getHomeErrors() + 1);
        InningScore is = inningScore(game, side, game.getInning());
        is.setErrors(is.getErrors() + 1);
        inningRepo.save(is);
    }

    private InningScore inningScore(Game game, TeamSide side, int inning) {
        return inningRepo.findByGameIdAndTeamSideAndInning(game.getId(), side, inning)
                .orElseGet(() -> inningRepo.save(InningScore.builder()
                        .game(game).teamSide(side).inning(inning).build()));
    }

    private void addEvent(Game game, String type, String playerName, String description, String color) {
        eventRepo.save(GameEvent.builder()
                .game(game).inning(game.getInning()).half(game.getHalf())
                .eventType(type).playerName(playerName)
                .description(description).colorTag(color)
                .actionSeq(game.getActionSeq())
                .build());
    }

    private void assertLive(Game game) {
        if (game.getStatus() == GameStatus.FINISHED) {
            throw new ApiException("比賽已結束，無法再記錄");
        }
        if (game.getStatus() == GameStatus.SCHEDULED) {
            game.setStatus(GameStatus.LIVE);
        }
    }

    private void touch(Game game) {
        game.setUpdatedAt(LocalDateTime.now());
        gameRepo.save(game);
    }

    // ------------------------------------------------------------ 快照 / 復原

    private void snapshot(Game game, String actionName) {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("inning", game.getInning());
            state.put("half", game.getHalf().name());
            state.put("outs", game.getOuts());
            state.put("balls", game.getBalls());
            state.put("strikes", game.getStrikes());
            state.put("runnerFirst", game.getRunnerFirst());
            state.put("runnerSecond", game.getRunnerSecond());
            state.put("runnerThird", game.getRunnerThird());
            state.put("awayScore", game.getAwayScore());
            state.put("homeScore", game.getHomeScore());
            state.put("awayHits", game.getAwayHits());
            state.put("homeHits", game.getHomeHits());
            state.put("awayErrors", game.getAwayErrors());
            state.put("homeErrors", game.getHomeErrors());
            state.put("awayBatterIndex", game.getAwayBatterIndex());
            state.put("homeBatterIndex", game.getHomeBatterIndex());
            state.put("status", game.getStatus().name());

            List<Map<String, Object>> innings = new ArrayList<>();
            for (InningScore is : inningRepo.findByGameIdOrderByInningAsc(game.getId())) {
                innings.add(Map.of("side", is.getTeamSide().name(), "inning", is.getInning(),
                        "runs", is.getRuns(), "hits", is.getHits(), "errors", is.getErrors()));
            }
            state.put("innings", innings);

            List<Map<String, Object>> lineups = new ArrayList<>();
            for (GameLineup l : lineupRepo.findByGameIdOrderByTeamSideAscBattingOrderAsc(game.getId())) {
                lineups.add(Map.of("id", l.getId(), "atBats", l.getAtBats(), "hits", l.getHits(), "rbi", l.getRbi(),
                        "stolenBases", l.getStolenBases(), "caughtStealing", l.getCaughtStealing()));
            }
            state.put("lineups", lineups);

            long seq = game.getActionSeq() + 1;
            game.setActionSeq(seq);
            snapshotRepo.save(GameSnapshot.builder()
                    .game(game).actionSeq(seq).actionName(actionName)
                    .stateJson(objectMapper.writeValueAsString(state))
                    .build());
        } catch (Exception e) {
            throw new ApiException("建立還原點失敗：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void restore(Game game, GameSnapshot snap) {
        try {
            Map<String, Object> state = objectMapper.readValue(snap.getStateJson(), Map.class);

            // 刪掉此動作之後產生的紀錄
            pitchRepo.deleteAll(pitchRepo.findByGameIdAndActionSeqGreaterThanEqual(game.getId(), snap.getActionSeq()));
            atBatRepo.deleteAll(atBatRepo.findByGameIdAndActionSeqGreaterThanEqual(game.getId(), snap.getActionSeq()));
            eventRepo.deleteAll(eventRepo.findByGameIdAndActionSeqGreaterThanEqual(game.getId(), snap.getActionSeq()));

            game.setInning(asInt(state.get("inning")));
            game.setHalf(InningHalf.valueOf((String) state.get("half")));
            game.setOuts(asInt(state.get("outs")));
            game.setBalls(asInt(state.get("balls")));
            game.setStrikes(asInt(state.get("strikes")));
            game.setRunnerFirst(asLong(state.get("runnerFirst")));
            game.setRunnerSecond(asLong(state.get("runnerSecond")));
            game.setRunnerThird(asLong(state.get("runnerThird")));
            game.setAwayScore(asInt(state.get("awayScore")));
            game.setHomeScore(asInt(state.get("homeScore")));
            game.setAwayHits(asInt(state.get("awayHits")));
            game.setHomeHits(asInt(state.get("homeHits")));
            game.setAwayErrors(asInt(state.get("awayErrors")));
            game.setHomeErrors(asInt(state.get("homeErrors")));
            game.setAwayBatterIndex(asInt(state.get("awayBatterIndex")));
            game.setHomeBatterIndex(asInt(state.get("homeBatterIndex")));
            game.setStatus(GameStatus.valueOf((String) state.get("status")));
            game.setActionSeq(snap.getActionSeq() - 1);

            inningRepo.deleteAll(inningRepo.findByGameIdOrderByInningAsc(game.getId()));
            for (Map<String, Object> m : (List<Map<String, Object>>) state.get("innings")) {
                inningRepo.save(InningScore.builder()
                        .game(game)
                        .teamSide(TeamSide.valueOf((String) m.get("side")))
                        .inning(asInt(m.get("inning")))
                        .runs(asInt(m.get("runs")))
                        .hits(asInt(m.get("hits")))
                        .errors(asInt(m.get("errors")))
                        .build());
            }
            for (Map<String, Object> m : (List<Map<String, Object>>) state.get("lineups")) {
                lineupRepo.findById(asLong(m.get("id"))).ifPresent(l -> {
                    l.setAtBats(asInt(m.get("atBats")));
                    l.setHits(asInt(m.get("hits")));
                    l.setRbi(asInt(m.get("rbi")));
                    l.setStolenBases(asInt(m.get("stolenBases")));
                    l.setCaughtStealing(asInt(m.get("caughtStealing")));
                    lineupRepo.save(l);
                });
            }
            touch(game);
        } catch (Exception e) {
            throw new ApiException("復原失敗：" + e.getMessage());
        }
    }

    private int asInt(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
    private Long asLong(Object o) { return o == null ? null : ((Number) o).longValue(); }
}
