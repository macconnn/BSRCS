package com.baseball.score.service;

import com.baseball.score.config.CurrentUser;
import com.baseball.score.dto.CreateGameRequest;
import com.baseball.score.dto.LineupEntryRequest;
import com.baseball.score.entity.*;
import com.baseball.score.enums.GameStatus;
import com.baseball.score.enums.TeamSide;
import com.baseball.score.repository.*;
import com.baseball.score.util.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 比賽 / 打線的建立與維護 */
@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepo;
    private final TeamRepository teamRepo;
    private final PlayerRepository playerRepo;
    private final GameLineupRepository lineupRepo;
    private final GameEditorRepository gameEditorRepo;
    private final GameEventRepository gameEventRepo;
    private final AtBatRepository atBatRepo;
    private final PitchRepository pitchRepo;
    private final InningScoreRepository inningRepo;
    private final GameSnapshotRepository snapshotRepo;
    private final GamePitcherStatRepository pitcherStatRepo;

    @Transactional
    public Game createGame(CreateGameRequest req, Long userId) {
        Team away = teamRepo.findById(req.getAwayTeamId())
                .orElseThrow(() -> new ApiException("找不到客隊"));
        Team home = teamRepo.findById(req.getHomeTeamId())
                .orElseThrow(() -> new ApiException("找不到主隊"));
        if (away.getId().equals(home.getId())) throw new ApiException("兩隊不可相同");

        Game game = gameRepo.save(Game.builder()
                .name(req.getName())
                .gameDate(req.getGameDate())
                .venue(req.getVenue())
                .remark(req.getRemark())
                .awayTeam(away).homeTeam(home)
                .totalInnings(req.getTotalInnings() == null ? 9 : req.getTotalInnings())
                .status(GameStatus.SCHEDULED)
                .createdBy(userId)
                .build());

        buildLineup(game, away, TeamSide.AWAY, req.getAwayLineup());
        buildLineup(game, home, TeamSide.HOME, req.getHomeLineup());
        return game;
    }

    /** 有帶手動打線就照編輯者安排的棒次建立，否則沿用舊規則自動排出 1~9 棒 */
    private void buildLineup(Game game, Team team, TeamSide side, List<LineupEntryRequest> entries) {
        if (entries != null && !entries.isEmpty()) {
            manualLineup(game, team, side, entries);
        } else {
            autoLineup(game, team, side);
        }
    }

    /** 以球員預設守備位置自動排出 1~9 棒（未手動安排打線時的預設規則） */
    private void autoLineup(Game game, Team team, TeamSide side) {
        List<Player> players = playerRepo.findByTeamIdAndActiveTrueOrderByIdAsc(team.getId());
        int order = 1;
        for (Player p : players) {
            if (order > 9) break;
            lineupRepo.save(GameLineup.builder()
                    .game(game).team(team).player(p).teamSide(side)
                    .battingOrder(order++)
                    .position(p.getDefaultPosition())
                    .starter(true).active(true)
                    .build());
        }
    }

    /** 依編輯者在「新增比賽」畫面手動安排的棒次 + 守備位置建立打線 */
    private void manualLineup(Game game, Team team, TeamSide side, List<LineupEntryRequest> entries) {
        Set<Integer> usedOrders = new HashSet<>();
        Set<Long> usedPlayers = new HashSet<>();
        for (LineupEntryRequest e : entries) {
            if (e.getPlayerId() == null || e.getBattingOrder() == null) {
                throw new ApiException(team.getName() + " 的打線資料不完整");
            }
            if (e.getBattingOrder() < 1) {
                throw new ApiException("棒次必須大於 0");
            }
            if (!usedOrders.add(e.getBattingOrder())) {
                throw new ApiException(team.getName() + " 的棒次 " + e.getBattingOrder() + " 重複");
            }
            if (!usedPlayers.add(e.getPlayerId())) {
                throw new ApiException("同一位球員不可在 " + team.getName() + " 打線中重複排入");
            }
            Player p = playerRepo.findById(e.getPlayerId())
                    .orElseThrow(() -> new ApiException("找不到球員 id=" + e.getPlayerId()));
            if (!p.getTeam().getId().equals(team.getId())) {
                throw new ApiException(p.getName() + " 不屬於 " + team.getName());
            }
            String position = (e.getPosition() == null || e.getPosition().isBlank())
                    ? p.getDefaultPosition() : e.getPosition().trim();
            lineupRepo.save(GameLineup.builder()
                    .game(game).team(team).player(p).teamSide(side)
                    .battingOrder(e.getBattingOrder())
                    .position(position)
                    .starter(true).active(true)
                    .build());
        }
    }

    // ---------------------------------------------------------- 比賽進行中換人

    /** 該隊目前可用來替補上場的球員（球隊名單中尚未在場上者） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> benchPlayers(Long gameId, TeamSide side) {
        Game game = gameRepo.findById(gameId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到比賽"));
        Team team = side == TeamSide.AWAY ? game.getAwayTeam() : game.getHomeTeam();

        Set<Long> onFieldPlayerIds = lineupRepo
                .findByGameIdAndTeamSideAndActiveTrueOrderByBattingOrderAsc(gameId, side)
                .stream().map(l -> l.getPlayer().getId()).collect(Collectors.toSet());

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Player p : playerRepo.findByTeamIdAndActiveTrueOrderByIdAsc(team.getId())) {
            if (onFieldPlayerIds.contains(p.getId())) continue; // 已經在場上的不顯示
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("jerseyNumber", p.getJerseyNumber() == null ? "" : p.getJerseyNumber());
            m.put("defaultPosition", p.getDefaultPosition() == null ? "" : p.getDefaultPosition());
            out.add(m);
        }
        return out;
    }

    /**
     * 換人：用板凳球員替補場上某位打線球員，沿用同一棒次。
     * 守備位置規則（2024 需求）：一律鎖死沿用「被換下球員（outLineupId）」當下在場上守的位置，
     * 例如場上 #13 守中外野手，換上 #43（他在球隊管理登記的守備位置是左外野手），
     * #43 上場後這場比賽的守備位置一樣是「中外野手」——只是暫時代守，
     * 不會去讀取、也完全不會更動 #43 在球隊管理（Player.defaultPosition）裡的守備位置資料。
     * 因此這裡刻意不接受呼叫端傳入的守備位置參數，避免任何管道用換上球員自己的守備位置覆蓋掉這個規則。
     */
    @Transactional
    public GameLineup substitute(Long gameId, TeamSide side, Long outLineupId, Long inPlayerId) {
        Game game = gameRepo.findById(gameId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到比賽"));
        if (game.getStatus() == GameStatus.FINISHED) {
            throw new ApiException("比賽已結束，無法換人");
        }

        GameLineup out = lineupRepo.findById(outLineupId)
                .orElseThrow(() -> new ApiException("找不到要替換下場的球員"));
        if (!out.getGame().getId().equals(gameId)) throw new ApiException("這位球員不屬於本場比賽");
        if (out.getTeamSide() != side) throw new ApiException("球隊不符，無法換人");
        if (!Boolean.TRUE.equals(out.getActive())) throw new ApiException("這位球員目前不在場上，無法被替換");

        Team team = side == TeamSide.AWAY ? game.getAwayTeam() : game.getHomeTeam();
        Player inPlayer = playerRepo.findById(inPlayerId)
                .orElseThrow(() -> new ApiException("找不到要換上場的球員"));
        if (!inPlayer.getTeam().getId().equals(team.getId())) {
            throw new ApiException(inPlayer.getName() + " 不屬於 " + team.getName());
        }
        if (inPlayer.getId().equals(out.getPlayer().getId())) {
            throw new ApiException("不可替換成同一位球員");
        }

        boolean alreadyOnField = lineupRepo
                .findByGameIdAndTeamSideAndActiveTrueOrderByBattingOrderAsc(gameId, side)
                .stream().anyMatch(l -> l.getPlayer().getId().equals(inPlayerId));
        if (alreadyOnField) throw new ApiException(inPlayer.getName() + " 已經在場上，無法重複換入");

        // 守備位置＝被換下球員（out）當下在場上守的位置，不是換上球員（inPlayer）在球隊管理裡的守備位置。
        String lockedPosition = out.getPosition();

        // 防呆：確認這個守備位置目前沒有被場上其他仍在場上的球員佔用（被換下的人本身除外），
        // 如果重複就阻擋這次換人。
        assertPositionNotDuplicated(gameId, side, lockedPosition, out.getId());

        out.setActive(false);
        lineupRepo.save(out);

        GameLineup in = lineupRepo.save(GameLineup.builder()
                .game(game).team(team).player(inPlayer).teamSide(side)
                .battingOrder(out.getBattingOrder())
                .position(lockedPosition)
                .starter(false).active(true)
                .build());

        syncPitcherPointer(game, side);
        game.setUpdatedAt(LocalDateTime.now());
        gameRepo.save(game);

        gameEventRepo.save(GameEvent.builder()
                .game(game).inning(game.getInning()).half(game.getHalf())
                .eventType("SUBSTITUTION")
                .playerName(inPlayer.getName())
                .description(out.getPlayer().getName() + " → " + inPlayer.getName()
                        + "（第 " + out.getBattingOrder() + " 棒"
                        + (lockedPosition == null || lockedPosition.isBlank() ? "" : "　" + lockedPosition) + "）")
                .colorTag("blue")
                .actionSeq(game.getActionSeq())
                .build());

        return in;
    }

    /** 單純互換兩位場上球員的守備位置，不涉及換人、不影響打線與棒次 */
    @Transactional
    public void swapPosition(Long gameId, TeamSide side, Long lineupIdA, Long lineupIdB) {
        Game game = gameRepo.findById(gameId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到比賽"));
        if (game.getStatus() == GameStatus.FINISHED) {
            throw new ApiException("比賽已結束，無法更換守備位置");
        }
        if (lineupIdA.equals(lineupIdB)) {
            throw new ApiException("請選擇兩位不同的球員");
        }

        GameLineup a = lineupRepo.findById(lineupIdA).orElseThrow(() -> new ApiException("找不到球員 A"));
        GameLineup b = lineupRepo.findById(lineupIdB).orElseThrow(() -> new ApiException("找不到球員 B"));
        for (GameLineup l : List.of(a, b)) {
            if (!l.getGame().getId().equals(gameId)) throw new ApiException("這位球員不屬於本場比賽");
            if (l.getTeamSide() != side) throw new ApiException("球隊不符，無法互換守備位置");
            if (!Boolean.TRUE.equals(l.getActive())) throw new ApiException(l.getPlayer().getName() + " 目前不在場上");
        }

        String posA = a.getPosition();
        String posB = b.getPosition();
        String labelA = (posA == null || posA.isBlank()) ? "-" : posA;
        String labelB = (posB == null || posB.isBlank()) ? "-" : posB;

        // 防呆：交換後 a 會變成 posB、b 會變成 posA，確認這兩個位置沒有被場上其他球員（a、b 本身除外）佔用，
        // 如果重複就阻擋這次互換。
        assertPositionNotDuplicated(gameId, side, posB, a.getId(), b.getId());
        assertPositionNotDuplicated(gameId, side, posA, a.getId(), b.getId());

        a.setPosition(posB);
        b.setPosition(posA);
        lineupRepo.save(a);
        lineupRepo.save(b);

        syncPitcherPointer(game, side);
        game.setUpdatedAt(LocalDateTime.now());
        gameRepo.save(game);

        gameEventRepo.save(GameEvent.builder()
                .game(game).inning(game.getInning()).half(game.getHalf())
                .eventType("POSITION_SWAP")
                .playerName(null)
                .description(a.getPlayer().getName() + "：" + labelA + " → " + labelB
                        + "　" + b.getPlayer().getName() + "：" + labelB + " → " + labelA)
                .colorTag("blue")
                .actionSeq(game.getActionSeq())
                .build());
    }

    /**
     * 防呆檢查：確認某個守備位置目前沒有被「場上其他仍在場上的球員」佔用，避免同一守備位置重複站人。
     * excludeLineupIds：檢查時要排除的打線項目 id（例如換人時排除被換下的人、互換守備位置時排除當事的兩人）。
     * position 為 null 或空字串時（例如尚未指定守備位置）不檢查。
     */
    private void assertPositionNotDuplicated(Long gameId, TeamSide side, String position, Long... excludeLineupIds) {
        if (position == null || position.isBlank()) return;
        java.util.Set<Long> excludes = java.util.Arrays.stream(excludeLineupIds).collect(java.util.stream.Collectors.toSet());
        boolean duplicated = lineupRepo
                .findByGameIdAndTeamSideAndActiveTrueOrderByBattingOrderAsc(gameId, side)
                .stream()
                .anyMatch(l -> !excludes.contains(l.getId()) && position.equals(l.getPosition()));
        if (duplicated) {
            throw new ApiException("守備位置「" + position + "」已經有球員在守備，不可重複");
        }
    }

    /** 依目前打線中「守備位置＝投手」的球員，同步 game.away/homePitcherLineupId 指標 */
    private void syncPitcherPointer(Game game, TeamSide side) {
        List<GameLineup> list = lineupRepo
                .findByGameIdAndTeamSideAndActiveTrueOrderByBattingOrderAsc(game.getId(), side);
        Long pitcherId = list.stream()
                .filter(l -> "投手".equals(l.getPosition()))
                .map(GameLineup::getId)
                .findFirst().orElse(null);
        if (side == TeamSide.AWAY) game.setAwayPitcherLineupId(pitcherId);
        else game.setHomePitcherLineupId(pitcherId);
    }

    /** 檢查此使用者是否可操作這場比賽：僅限「建立者本人」或「管理員」，避免多人同時記錄同一場比賽造成資料錯亂 */
    public void assertCanEditGame(Long gameId, CurrentUser user) {
        if (user.getUserId() == null) throw new ApiException(HttpStatus.FORBIDDEN, "請先登入編輯者帳號");
        if (user.isAdmin()) return;
        Game game = gameRepo.findById(gameId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到比賽"));
        if (!user.getUserId().equals(game.getCreatedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有建立這場比賽的編輯者才能操作，避免多人同時記錄造成資料錯亂");
        }
    }

    /**
     * 刪除整場比賽（含所有關聯紀錄：投球、打席、每局比分、打線、事件、快照、協同編輯者）。
     * 因為資料庫的外鍵沒有設定 ON DELETE CASCADE，必須依「子表 → 父表」順序手動刪除，
     * 否則會因外鍵限制而刪除失敗。
     */
    @Transactional
    public void deleteGame(Long gameId) {
        Game game = gameRepo.findById(gameId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到比賽"));

        pitchRepo.deleteByGameId(gameId);
        atBatRepo.deleteByGameId(gameId);
        inningRepo.deleteByGameId(gameId);
        gameEventRepo.deleteByGameId(gameId);
        snapshotRepo.deleteByGameId(gameId);
        pitcherStatRepo.deleteByGameId(gameId);
        lineupRepo.deleteByGameId(gameId);
        gameEditorRepo.deleteByGameId(gameId);

        gameRepo.delete(game);
    }
}
