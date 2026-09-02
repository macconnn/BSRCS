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
    private final GamePitcherStatRepository pitcherStatRepo;
    private final PlayerRepository playerRepo;
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

    /**
     * 依「打線 id」找出（或建立）這位投手在這場比賽的投手數據列。
     * 找不到指定投手（pitcherLineupId 是 null，或該打線項目查無資料）時回傳 null，呼叫端要自行判斷跳過，
     * 避免因為打線還沒設好投手而整支動作報錯。
     * 新建立的列會標記目前的 action_seq，undo 復原時要靠這個判斷「這一列是不是這次動作才新增的」。
     */
    private GamePitcherStat pitcherStatFor(Game game, Long pitcherLineupId) {
        if (pitcherLineupId == null) return null;
        GameLineup pitcher = lineupRepo.findById(pitcherLineupId).orElse(null);
        if (pitcher == null) return null;
        return pitcherStatRepo.findByGameIdAndPlayerId(game.getId(), pitcher.getPlayer().getId())
                .orElseGet(() -> pitcherStatRepo.save(GamePitcherStat.builder()
                        .game(game).team(pitcher.getTeam()).player(pitcher.getPlayer())
                        .actionSeq(game.getActionSeq())
                        .build()));
    }

    /**
     * 依「球員 id」直接找出（或建立）這位投手在這場比賽的投手數據列。
     * 用於失分歸屬：壘上跑者得分時，要算在「當初讓他上壘的那位投手」身上，而不是現在正在投球的投手，
     * 所以這裡是用球員 id（跟著跑者一起存在 game.runnerXxxPitcherId），不是「現在打席」的 pitcherLineupId。
     */
    private GamePitcherStat pitcherStatForPlayer(Game game, Long pitcherPlayerId) {
        if (pitcherPlayerId == null) return null;
        Player pitcher = playerRepo.findById(pitcherPlayerId).orElse(null);
        if (pitcher == null) return null;
        return pitcherStatRepo.findByGameIdAndPlayerId(game.getId(), pitcherPlayerId)
                .orElseGet(() -> pitcherStatRepo.save(GamePitcherStat.builder()
                        .game(game).team(pitcher.getTeam()).player(pitcher)
                        .actionSeq(game.getActionSeq())
                        .build()));
    }

    /** atBat.pitcherLineupId（打線列 id）換算成穩定的球員 id，供壘包責任歸屬追蹤使用 */
    private Long pitcherPlayerIdOf(Long pitcherLineupId) {
        if (pitcherLineupId == null) return null;
        return lineupRepo.findById(pitcherLineupId).map(l -> l.getPlayer().getId()).orElse(null);
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

        GamePitcherStat pStat = pitcherStatFor(game, atBat.getPitcherLineupId());
        if (pStat != null) {
            pStat.setPitches(pStat.getPitches() + 1);
            pitcherStatRepo.save(pStat);
        }

        // 球種／球速欄位可能因為「球種球速開關」被關閉而沒有顯示，前端就不會送這兩個值上來（null）；
        // 這裡統一補上預設值「直球」／100 km/h，確保資料庫欄位不會是空的，跟關閉開關時畫面上講的行為一致。
        String finalPitchType = (pitchType == null || pitchType.isBlank()) ? "直球" : pitchType;
        Integer finalSpeedKmh = speedKmh == null ? 100 : speedKmh;

        switch (call) {
            case STRIKE -> game.setStrikes(game.getStrikes() + 1);
            case BALL -> game.setBalls(game.getBalls() + 1);
            case FOUL -> { if (game.getStrikes() < 2) game.setStrikes(game.getStrikes() + 1); }
        }

        int seq = pitchRepo.findByAtBatIdOrderBySeqNoDesc(atBat.getId()).stream()
                .mapToInt(Pitch::getSeqNo).max().orElse(0) + 1;
        pitchRepo.save(Pitch.builder()
                .game(game).atBat(atBat).seqNo(seq).call(call)
                .pitchType(finalPitchType).speedKmh(finalSpeedKmh)
                .ballsAfter(game.getBalls()).strikesAfter(game.getStrikes())
                .actionSeq(game.getActionSeq())
                .build());

        addEvent(game, "PITCH", batter.getPlayer().getName(), call.getLabel(),
                switch (call) { case STRIKE -> "yellow"; case BALL -> "green"; case FOUL -> "blue"; });

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
        Long[] pids = { game.getRunnerFirstPitcherId(), game.getRunnerSecondPitcherId(), game.getRunnerThirdPitcherId() };
        int idx = fromBase - 1;
        Long runnerId = bases[idx];
        Long runnerPitcherId = pids[idx];
        if (runnerId == null) {
            throw new ApiException(baseLabel(fromBase) + " 目前沒有跑者，無法盜壘");
        }
        GameLineup runner = lineupRepo.findById(runnerId)
                .orElseThrow(() -> new ApiException("找不到跑者資料"));
        TeamSide batting = battingSide(game);
        String runnerName = runner.getPlayer().getName();

        if (outcome == StealOutcome.CAUGHT) {
            bases[idx] = null; pids[idx] = null;
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

            bases[idx] = null; pids[idx] = null;
            int runs = 0;
            if (toBase == 4) {
                runs = 1;
            } else {
                bases[toBase - 1] = runnerId; pids[toBase - 1] = runnerPitcherId;
            }

            runner.setStolenBases(runner.getStolenBases() + 1);
            lineupRepo.save(runner);
            if (error) addError(game, fieldingSide(game));
            if (runs > 0) addRuns(game, batting, runs);

            // 被盜壘（這次盜壘本身）：算在「目前正在守備的投手」身上（用當下的 currentPitcher 動態查，
            // 因為盜壘不像安打／保送隸屬於某個特定打席，是打席進行中獨立發生的跑者事件）。
            GameLineup pitcherNow = currentPitcher(game);
            Long currentPitcherPlayerId = pitcherNow == null ? null : pitcherNow.getPlayer().getId();
            GamePitcherStat pStat = pitcherStatFor(game, pitcherNow == null ? null : pitcherNow.getId());
            if (pStat != null) {
                pStat.setStolenBasesAllowed(pStat.getStolenBasesAllowed() + 1);
                pitcherStatRepo.save(pStat);
            }
            // 盜本壘得分：失分要算在「原本讓這位跑者上壘的投手」身上，不是現在守備的投手（可能剛好是同一人）。
            if (runs > 0) {
                Long chargeTo = runnerPitcherId != null ? runnerPitcherId : currentPitcherPlayerId;
                GamePitcherStat rStat = pitcherStatForPlayer(game, chargeTo);
                if (rStat != null) {
                    rStat.setRunsAllowed(rStat.getRunsAllowed() + runs);
                    pitcherStatRepo.save(rStat);
                }
            }

            String desc = runnerName + " 從" + baseLabel(fromBase) + "盜上"
                    + (toBase == 4 ? "本壘，得 1 分" : baseLabel(toBase))
                    + (error ? "（含守備失誤）" : "");
            addEvent(game, "STEAL", runnerName, desc, "blue");
        }

        game.setRunnerFirst(bases[0]);
        game.setRunnerSecond(bases[1]);
        game.setRunnerThird(bases[2]);
        game.setRunnerFirstPitcherId(pids[0]);
        game.setRunnerSecondPitcherId(pids[1]);
        game.setRunnerThirdPitcherId(pids[2]);

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
        Long[] pids = { game.getRunnerFirstPitcherId(), game.getRunnerSecondPitcherId(), game.getRunnerThirdPitcherId() };
        int idx = fromBase - 1;
        Long runnerId = bases[idx];
        Long runnerPitcherId = pids[idx];
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

        bases[idx] = null; pids[idx] = null;
        int runs = 0;
        if (toBase == 4) {
            runs = 1;
        } else {
            bases[toBase - 1] = runnerId; pids[toBase - 1] = runnerPitcherId;
        }

        addError(game, fieldingSide(game));
        if (runs > 0) addRuns(game, batting, runs);

        // 失分算在「原本讓這位跑者上壘的投手」身上；如果查不到（例如舊資料沒有記錄），退回算在目前守備的投手身上。
        if (runs > 0) {
            Long chargeTo = runnerPitcherId;
            if (chargeTo == null) {
                GameLineup pitcherNow = currentPitcher(game);
                chargeTo = pitcherNow == null ? null : pitcherNow.getPlayer().getId();
            }
            GamePitcherStat rStat = pitcherStatForPlayer(game, chargeTo);
            if (rStat != null) {
                rStat.setRunsAllowed(rStat.getRunsAllowed() + runs);
                pitcherStatRepo.save(rStat);
            }
        }

        game.setRunnerFirst(bases[0]);
        game.setRunnerSecond(bases[1]);
        game.setRunnerThird(bases[2]);
        game.setRunnerFirstPitcherId(pids[0]);
        game.setRunnerSecondPitcherId(pids[1]);
        game.setRunnerThirdPitcherId(pids[2]);

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

        // 手動調整壘包沒有對應的「打席」或「盜壘事件」可以追蹤責任投手，只能用最保守的猜測：
        // 這個壘包上的球員如果變了（換了一個人，或原本沒人現在有人），就當成是「現在守備的投手」讓他上壘的；
        // 如果壘包上的人沒變（只是重新確認一次），保留原本記錄的責任投手，不要覆蓋掉。
        GameLineup pitcherNow = currentPitcher(game);
        Long currentPitcherPlayerId = pitcherNow == null ? null : pitcherNow.getPlayer().getId();
        game.setRunnerFirstPitcherId(java.util.Objects.equals(game.getRunnerFirst(), firstId)
                ? game.getRunnerFirstPitcherId() : (firstId == null ? null : currentPitcherPlayerId));
        game.setRunnerSecondPitcherId(java.util.Objects.equals(game.getRunnerSecond(), secondId)
                ? game.getRunnerSecondPitcherId() : (secondId == null ? null : currentPitcherPlayerId));
        game.setRunnerThirdPitcherId(java.util.Objects.equals(game.getRunnerThird(), thirdId)
                ? game.getRunnerThirdPitcherId() : (thirdId == null ? null : currentPitcherPlayerId));

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
        pitcherStatRepo.deleteByGameId(gameId);

        game.setInning(1); game.setHalf(InningHalf.TOP);
        game.setOuts(0); game.setBalls(0); game.setStrikes(0);
        game.setRunnerFirst(null); game.setRunnerSecond(null); game.setRunnerThird(null);
        game.setRunnerFirstPitcherId(null); game.setRunnerSecondPitcherId(null); game.setRunnerThirdPitcherId(null);
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
        // 每個壘包跑者「是被哪位投手放上壘的」（存球員 id），跟著跑者一起搬動；
        // 換投手後，壘上原本的跑者如果之後得分，失分才能正確算在原本讓他上壘的投手身上，而不是新投手。
        Long[] pids = { game.getRunnerFirstPitcherId(), game.getRunnerSecondPitcherId(), game.getRunnerThirdPitcherId() };
        // 這個打席「目前實際面對打者」的投手（新上場的投手一開始都是這個角色）
        Long thisPitcherPlayerId = pitcherPlayerIdOf(atBat.getPitcherLineupId());
        // 這一球會產生的失分，依「造成上壘的投手」個別歸屬；同一個投手可能同時對到好幾筆（例如滿貫全壘打）
        Map<Long, Integer> runsByPitcher = new LinkedHashMap<>();
        int runs = 0;

        if (result == PlayResult.HOME_RUN) {
            for (int i = 0; i < 3; i++) {
                if (bases[i] != null) {
                    runs++;
                    runsByPitcher.merge(pids[i] != null ? pids[i] : thisPitcherPlayerId, 1, Integer::sum);
                    bases[i] = null; pids[i] = null;
                }
            }
            runs++; // 打者本人
            runsByPitcher.merge(thisPitcherPlayerId, 1, Integer::sum);
        } else if (result == PlayResult.WALK || result == PlayResult.HIT_BY_PITCH) {
            // 保送：只推進被擠壓的跑者
            if (bases[0] != null) {
                if (bases[1] != null) {
                    if (bases[2] != null) {
                        runs++;
                        runsByPitcher.merge(pids[2] != null ? pids[2] : thisPitcherPlayerId, 1, Integer::sum);
                    }
                    bases[2] = bases[1]; pids[2] = pids[1];
                }
                bases[1] = bases[0]; pids[1] = pids[0];
            }
            bases[0] = batter.getId(); pids[0] = thisPitcherPlayerId;
        } else {
            int adv = result.getRunnerAdvance();
            if (adv > 0) {
                for (int i = 2; i >= 0; i--) {
                    if (bases[i] == null) continue;
                    int target = i + adv;               // 0=一壘,1=二壘,2=三壘
                    if (target >= 3) {
                        runs++;
                        runsByPitcher.merge(pids[i] != null ? pids[i] : thisPitcherPlayerId, 1, Integer::sum);
                        bases[i] = null; pids[i] = null;
                    } else {
                        bases[target] = bases[i]; pids[target] = pids[i];
                        bases[i] = null; pids[i] = null;
                    }
                }
            }
            if (result == PlayResult.DOUBLE_PLAY && bases[0] != null) { bases[0] = null; pids[0] = null; }
            if (result == PlayResult.CAUGHT_STEALING) {
                for (int i = 2; i >= 0; i--) if (bases[i] != null) { bases[i] = null; pids[i] = null; break; }
            }
            int b = result.getBases();
            if (b >= 1 && b <= 3) { bases[b - 1] = batter.getId(); pids[b - 1] = thisPitcherPlayerId; }
        }

        game.setRunnerFirst(bases[0]);
        game.setRunnerSecond(bases[1]);
        game.setRunnerThird(bases[2]);
        game.setRunnerFirstPitcherId(pids[0]);
        game.setRunnerSecondPitcherId(pids[1]);
        game.setRunnerThirdPitcherId(pids[2]);

        // 分數 / 安打 / 失誤
        if (runs > 0) addRuns(game, batting, runs);
        if (result.isHit()) addHit(game, batting);
        if (result.isError()) addError(game, fieldingSide(game));
        if (result.getOuts() > 0) game.setOuts(game.getOuts() + result.getOuts());

        // 投手數據（局數 / 被安打 / 四壞 / 觸身球）：歸給「這個打席開始時，場上守備的那位投手」（atBat.pitcherLineupId），
        // 而不是重新查一次目前的投手，確保就算這個打席結束後緊接著換投手，這個打席的數據仍算在原本那位投手身上。
        // 失分不在這裡算，改用下面的 runsByPitcher 依each 跑者原本的責任投手個別歸屬。
        GamePitcherStat pStat = pitcherStatFor(game, atBat.getPitcherLineupId());
        if (pStat != null) {
            pStat.setInningsOuts(pStat.getInningsOuts() + result.getOuts());
            if (result.isHit()) pStat.setHitsAllowed(pStat.getHitsAllowed() + 1);
            switch (result) {
                case DOUBLE -> pStat.setDoublesAllowed(pStat.getDoublesAllowed() + 1);
                case TRIPLE -> pStat.setTriplesAllowed(pStat.getTriplesAllowed() + 1);
                case HOME_RUN -> pStat.setHomeRunsAllowed(pStat.getHomeRunsAllowed() + 1);
                case WALK -> pStat.setWalksAllowed(pStat.getWalksAllowed() + 1);
                case HIT_BY_PITCH -> pStat.setHitByPitchAllowed(pStat.getHitByPitchAllowed() + 1);
                default -> { }
            }
            pitcherStatRepo.save(pStat);
        }
        for (Map.Entry<Long, Integer> e : runsByPitcher.entrySet()) {
            if (e.getKey() == null) continue;
            GamePitcherStat rStat = pitcherStatForPlayer(game, e.getKey());
            if (rStat != null) {
                rStat.setRunsAllowed(rStat.getRunsAllowed() + e.getValue());
                pitcherStatRepo.save(rStat);
            }
        }

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
            // 記錄快照當下「正在進行中、尚未結束」的打席 id（如果有的話）。
            // 之所以需要這個，是因為 recordResult()／nextBatter() 這類動作，
            // 有時是「修改」一筆已存在的打席（把它標記為結束、填入結果），而不是新建一筆；
            // 復原時單靠「刪除快照之後新增的紀錄」抓不到這種修改，必須額外把這筆打席重置回進行中狀態。
            AtBat activeAtBat = atBatRepo.findFirstByGameIdAndFinishedFalseOrderBySeqNoDesc(game.getId()).orElse(null);
            state.put("activeAtBatId", activeAtBat == null ? null : activeAtBat.getId());
            state.put("inning", game.getInning());
            state.put("half", game.getHalf().name());
            state.put("outs", game.getOuts());
            state.put("balls", game.getBalls());
            state.put("strikes", game.getStrikes());
            state.put("runnerFirst", game.getRunnerFirst());
            state.put("runnerSecond", game.getRunnerSecond());
            state.put("runnerThird", game.getRunnerThird());
            state.put("runnerFirstPitcherId", game.getRunnerFirstPitcherId());
            state.put("runnerSecondPitcherId", game.getRunnerSecondPitcherId());
            state.put("runnerThirdPitcherId", game.getRunnerThirdPitcherId());
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

            // 投手數據：快照當下「已經存在」的每一列現況（還沒存在的列，代表是這次動作才要新建立的，
            // 不需要記進快照——復原時直接靠 actionSeq 把這種新列整列刪掉即可，見 restore()）。
            List<Map<String, Object>> pitcherStats = new ArrayList<>();
            for (GamePitcherStat ps : pitcherStatRepo.findByGameId(game.getId())) {
                Map<String, Object> psState = new LinkedHashMap<>();
                psState.put("id", ps.getId());
                psState.put("inningsOuts", ps.getInningsOuts());
                psState.put("pitches", ps.getPitches());
                psState.put("runsAllowed", ps.getRunsAllowed());
                psState.put("hitsAllowed", ps.getHitsAllowed());
                psState.put("doublesAllowed", ps.getDoublesAllowed());
                psState.put("triplesAllowed", ps.getTriplesAllowed());
                psState.put("homeRunsAllowed", ps.getHomeRunsAllowed());
                psState.put("walksAllowed", ps.getWalksAllowed());
                psState.put("hitByPitchAllowed", ps.getHitByPitchAllowed());
                psState.put("stolenBasesAllowed", ps.getStolenBasesAllowed());
                pitcherStats.add(psState);
            }
            state.put("pitcherStats", pitcherStats);

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
            // 投手數據列如果是這次動作才新建立的（例如這是這位投手在這場比賽第一次被記到數據），
            // 快照當時根本還不存在這一列，直接整列刪掉；如果快照當時已經存在，則保留、下面再依快照值還原欄位。
            pitcherStatRepo.deleteAll(pitcherStatRepo.findByGameIdAndActionSeqGreaterThanEqual(game.getId(), snap.getActionSeq()));

            // 把快照當時「正在進行中」的打席重置回進行中狀態：
            // 如果這筆打席在快照之後被 recordResult()／nextBatter() 標記為已結束（例如誤按成一壘安打），
            // 上面的刪除條件抓不到它（因為它是被「修改」而不是「新增」），這裡明確把它重置回乾淨的進行中狀態，
            // 這樣重新記錄正確結果時，才會接續回同一筆打席，而不是另外新開一筆。
            Long activeAtBatId = asLong(state.get("activeAtBatId"));
            if (activeAtBatId != null) {
                atBatRepo.findById(activeAtBatId).ifPresent(ab -> {
                    ab.setFinished(false);
                    ab.setResult(null);
                    ab.setRbi(0);
                    ab.setOutsRecorded(0);
                    ab.setRunsScored(0);
                    ab.setDescription(null);
                    atBatRepo.save(ab);
                });
            }

            game.setInning(asInt(state.get("inning")));
            game.setHalf(InningHalf.valueOf((String) state.get("half")));
            game.setOuts(asInt(state.get("outs")));
            game.setBalls(asInt(state.get("balls")));
            game.setStrikes(asInt(state.get("strikes")));
            game.setRunnerFirst(asLong(state.get("runnerFirst")));
            game.setRunnerSecond(asLong(state.get("runnerSecond")));
            game.setRunnerThird(asLong(state.get("runnerThird")));
            // 這兩個欄位是後來才加的，舊快照（state_json 裡沒有這個 key）會是 null，
            // asLong(null) 也是回傳 null，行為上等於「這場比賽在還原的當下還沒有責任投手資料」，安全、不會噴錯。
            game.setRunnerFirstPitcherId(asLong(state.get("runnerFirstPitcherId")));
            game.setRunnerSecondPitcherId(asLong(state.get("runnerSecondPitcherId")));
            game.setRunnerThirdPitcherId(asLong(state.get("runnerThirdPitcherId")));
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
            // 注意：deleteAll() 後緊接著 save() 新資料時，Hibernate 預設會把「INSERT」排在「DELETE」之前送出，
            // 這裡的新資料跟剛刪除的資料可能是同一組 (game_id, team_side, inning)，沒有先 flush 的話
            // 會因為舊資料實際上還沒被刪除，插入時撞到 uk_inning_score 唯一索引而失敗。
            inningRepo.flush();
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
            Object pitcherStatsState = state.get("pitcherStats");
            if (pitcherStatsState != null) {
                for (Map<String, Object> m : (List<Map<String, Object>>) pitcherStatsState) {
                    pitcherStatRepo.findById(asLong(m.get("id"))).ifPresent(ps -> {
                        ps.setInningsOuts(asInt(m.get("inningsOuts")));
                        ps.setPitches(asInt(m.get("pitches")));
                        ps.setRunsAllowed(asInt(m.get("runsAllowed")));
                        ps.setHitsAllowed(asInt(m.get("hitsAllowed")));
                        ps.setDoublesAllowed(asInt(m.get("doublesAllowed")));
                        ps.setTriplesAllowed(asInt(m.get("triplesAllowed")));
                        ps.setHomeRunsAllowed(asInt(m.get("homeRunsAllowed")));
                        ps.setWalksAllowed(asInt(m.get("walksAllowed")));
                        ps.setHitByPitchAllowed(asInt(m.get("hitByPitchAllowed")));
                        ps.setStolenBasesAllowed(asInt(m.get("stolenBasesAllowed")));
                        pitcherStatRepo.save(ps);
                    });
                }
            }
            touch(game);
        } catch (Exception e) {
            throw new ApiException("復原失敗：" + e.getMessage());
        }
    }

    private int asInt(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
    private Long asLong(Object o) { return o == null ? null : ((Number) o).longValue(); }
}
