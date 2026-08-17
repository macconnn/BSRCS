package com.baseball.score.service;

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

    /** 換人：用板凳球員替補場上某位打線球員（沿用同一棒次，守備位置可另外指定） */
    @Transactional
    public GameLineup substitute(Long gameId, TeamSide side, Long outLineupId, Long inPlayerId, String position) {
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

        out.setActive(false);
        lineupRepo.save(out);

        String pos = (position == null || position.isBlank()) ? out.getPosition() : position.trim();
        GameLineup in = lineupRepo.save(GameLineup.builder()
                .game(game).team(team).player(inPlayer).teamSide(side)
                .battingOrder(out.getBattingOrder())
                .position(pos)
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
                        + "（第 " + out.getBattingOrder() + " 棒" + (pos == null || pos.isBlank() ? "" : "　" + pos) + "）")
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

    /** 檢查此使用者是否可編輯這場比賽（第一次編輯時自動登記為協同記錄員） */
    @Transactional
    public void assertCanEditGame(Long gameId, Long userId) {
        if (userId == null) throw new ApiException(HttpStatus.FORBIDDEN, "請先登入編輯者帳號");
        Game game = gameRepo.findById(gameId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到比賽"));
        boolean owner = userId.equals(game.getCreatedBy());
        if (!owner && !gameEditorRepo.existsByGameIdAndUserId(gameId, userId)) {
            // 預設允許所有已登入的編輯者共同記錄；若要嚴格限制，改成 throw
            gameEditorRepo.save(GameEditor.builder().gameId(gameId).userId(userId).build());
        }
    }
}
