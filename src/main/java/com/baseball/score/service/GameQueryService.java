package com.baseball.score.service;

import com.baseball.score.entity.*;
import com.baseball.score.enums.GameStatus;
import com.baseball.score.enums.TeamSide;
import com.baseball.score.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;

/** 組出前端（PC / Mobile、編輯 / 檢視共用）需要的完整比賽狀態 JSON。 */
@Service
@RequiredArgsConstructor
public class GameQueryService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final GameRepository gameRepo;
    private final GameLineupRepository lineupRepo;
    private final InningScoreRepository inningRepo;
    private final GameEventRepository eventRepo;
    private final AtBatRepository atBatRepo;
    private final PitchRepository pitchRepo;
    private final ScoringService scoring;
    private final PlayerStatsService statsService;

    @Transactional(readOnly = true)
    public Map<String, Object> gameState(Long gameId, boolean canEdit) {
        Game game = scoring.getGame(gameId);
        TeamSide batting = scoring.battingSide(game);
        TeamSide fielding = scoring.fieldingSide(game);

        List<GameLineup> away = scoring.lineup(game, TeamSide.AWAY);
        List<GameLineup> home = scoring.lineup(game, TeamSide.HOME);

        List<GameLineup> battingList = batting == TeamSide.AWAY ? away : home;
        GameLineup batter = battingList.isEmpty() ? null : scoring.currentBatter(game);
        GameLineup next = battingList.isEmpty() ? null : scoring.nextBatter(game);
        GameLineup pitcher = scoring.currentPitcher(game);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("canEdit", canEdit);
        root.put("serverTime", java.time.LocalDateTime.now().format(TIME_FMT));

        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", game.getId());
        g.put("name", game.getName());
        g.put("gameDate", game.getGameDate() == null ? "-" : game.getGameDate().format(DATE_FMT));
        g.put("venue", orDash(game.getVenue()));
        g.put("remark", orDash(game.getRemark()));
        g.put("status", game.getStatus().name());
        g.put("statusLabel", statusLabel(game.getStatus()));
        g.put("inning", game.getInning());
        g.put("half", game.getHalf().name());
        g.put("inningLabel", game.getInning() + "局" + game.getHalf().getLabel());
        g.put("totalInnings", game.getTotalInnings());
        g.put("outs", game.getOuts());
        g.put("balls", game.getBalls());
        g.put("strikes", game.getStrikes());
        g.put("countLabel", game.getBalls() + " - " + game.getStrikes());
        g.put("battingSide", batting.name());
        g.put("bases", Map.of(
                "first", game.getRunnerFirst() != null,
                "second", game.getRunnerSecond() != null,
                "third", game.getRunnerThird() != null));
        root.put("game", g);

        root.put("away", teamBlock(game.getAwayTeam(), game.getAwayScore(), game.getAwayHits(), game.getAwayErrors()));
        root.put("home", teamBlock(game.getHomeTeam(), game.getHomeScore(), game.getHomeHits(), game.getHomeErrors()));

        root.put("awayLineup", lineupBlock(away, batter, next));
        root.put("homeLineup", lineupBlock(home, batter, next));

        root.put("currentBatter", playerBlock(batter));
        root.put("nextBatter", playerBlock(next));
        root.put("pitcher", playerBlock(pitcher));
        root.put("defenseSide", fielding.name());
        root.put("defenseTeamName", fielding == TeamSide.AWAY ? game.getAwayTeam().getName() : game.getHomeTeam().getName());
        root.put("defense", defenseBlock(fielding == TeamSide.AWAY ? away : home, pitcher));

        root.put("pitches", scoring.pitchesOfCurrentAtBat(game).stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", p.getSeqNo());
            m.put("call", p.getCall().name());
            m.put("callLabel", p.getCall().getLabel());
            m.put("pitchType", orDash(p.getPitchType()));
            m.put("speed", p.getSpeedKmh() == null ? "-" : p.getSpeedKmh() + " km/h");
            m.put("count", p.getBallsAfter() + "-" + p.getStrikesAfter());
            return m;
        }).toList());

        root.put("events", eventRepo.findByGameIdOrderByIdDesc(gameId, PageRequest.of(0, 8)).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inningLabel", e.getInning() + "局" + e.getHalf().getLabel());
            m.put("player", orDash(e.getPlayerName()));
            m.put("description", e.getDescription());
            m.put("color", e.getColorTag() == null ? "gray" : e.getColorTag());
            return m;
        }).toList());

        root.put("lastResult", atBatRepo.findFirstByGameIdAndFinishedTrueOrderBySeqNoDesc(gameId)
                .map(AtBat::getDescription).orElse(null));
        root.put("scoreboard", scoreboard(game));
        return root;
    }

    /** 記分板：每局得分 + R / H / E */
    private Map<String, Object> scoreboard(Game game) {
        Map<Integer, Integer> awayRuns = new HashMap<>();
        Map<Integer, Integer> homeRuns = new HashMap<>();
        for (InningScore is : inningRepo.findByGameIdOrderByInningAsc(game.getId())) {
            if (is.getTeamSide() == TeamSide.AWAY) awayRuns.put(is.getInning(), is.getRuns());
            else homeRuns.put(is.getInning(), is.getRuns());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int maxInning = Math.max(game.getTotalInnings(), game.getInning());
        for (int i = 1; i <= maxInning; i++) {
            boolean awayPlayed = i < game.getInning() || (i == game.getInning());
            boolean homePlayed = i < game.getInning()
                    || (i == game.getInning() && game.getHalf() == com.baseball.score.enums.InningHalf.BOTTOM);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inning", i);
            m.put("away", awayPlayed ? String.valueOf(awayRuns.getOrDefault(i, 0)) : "-");
            m.put("home", homePlayed ? String.valueOf(homeRuns.getOrDefault(i, 0)) : "-");
            m.put("current", i == game.getInning());
            rows.add(m);
        }
        return Map.of(
                "innings", rows,
                "awayTotal", Map.of("r", game.getAwayScore(), "h", game.getAwayHits(), "e", game.getAwayErrors()),
                "homeTotal", Map.of("r", game.getHomeScore(), "h", game.getHomeHits(), "e", game.getHomeErrors()));
    }

    private Map<String, Object> teamBlock(Team team, int score, int hits, int errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", team.getId());
        m.put("name", team.getName());
        m.put("shortName", orDash(team.getShortName()));
        m.put("color", team.getColorHex() == null ? "#1d4ed8" : team.getColorHex());
        m.put("score", score);
        m.put("hits", hits);
        m.put("errors", errors);
        return m;
    }

    private List<Map<String, Object>> lineupBlock(List<GameLineup> list, GameLineup current, GameLineup next) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GameLineup l : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineupId", l.getId());
            m.put("order", l.getBattingOrder());
            m.put("number", orDash(l.getPlayer().getJerseyNumber()));
            m.put("name", l.getPlayer().getName());
            m.put("position", orDash(l.getPosition()));
            m.put("avg", statsService.avgText(statsService.careerStats(l.getPlayer().getId()).avg()));
            m.put("today", l.getAtBats() + "-" + l.getHits());
            m.put("current", current != null && current.getId().equals(l.getId()));
            m.put("next", next != null && next.getId().equals(l.getId()));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> playerBlock(GameLineup l) {
        if (l == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineupId", l.getId());
        m.put("order", l.getBattingOrder());
        m.put("number", orDash(l.getPlayer().getJerseyNumber()));
        m.put("name", l.getPlayer().getName());
        m.put("position", orDash(l.getPosition()));
        m.put("avg", statsService.avgText(statsService.careerStats(l.getPlayer().getId()).avg()));
        m.put("today", l.getAtBats() + "-" + l.getHits());
        return m;
    }

    /** 守備陣型：九宮格位置 → 球員 */
    private List<Map<String, Object>> defenseBlock(List<GameLineup> fielding, GameLineup pitcher) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GameLineup l : fielding) {
            if (l.getPosition() == null || "指定打擊".equals(l.getPosition())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("position", l.getPosition());
            m.put("number", orDash(l.getPlayer().getJerseyNumber()));
            m.put("name", l.getPlayer().getName());
            out.add(m);
        }
        if (pitcher != null && out.stream().noneMatch(m -> "投手".equals(m.get("position")))) {
            out.add(new LinkedHashMap<>(Map.of(
                    "position", "投手",
                    "number", orDash(pitcher.getPlayer().getJerseyNumber()),
                    "name", pitcher.getPlayer().getName())));
        }
        return out;
    }

    // ------------------------------------------------------------ 球員本場表現

    /** 點擊打線中的球員姓名時顯示：每個打席的好壞球過程與結果 */
    @Transactional(readOnly = true)
    public Map<String, Object> playerLog(Long gameId, Long lineupId) {
        GameLineup lineup = lineupRepo.findById(lineupId)
                .orElseThrow(() -> new com.baseball.score.util.ApiException("找不到這位球員的出賽紀錄"));
        if (!lineup.getGame().getId().equals(gameId)) {
            throw new com.baseball.score.util.ApiException("這位球員不屬於本場比賽");
        }

        List<Map<String, Object>> atBats = new ArrayList<>();
        int hits = 0, rbi = 0, walks = 0, strikeouts = 0;

        for (AtBat ab : atBatRepo.findByGameIdAndBatterLineupIdOrderBySeqNoAsc(gameId, lineupId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seqNo", ab.getSeqNo());
            m.put("inningLabel", ab.getInning() + "局" + ab.getHalf().getLabel());
            m.put("result", ab.getResult() == null ? null : ab.getResult().name());
            m.put("resultLabel", ab.getResult() == null ? "進行中" : ab.getResult().getLabel());
            m.put("resultColor", ab.getResult() == null ? "gray" : resultColor(ab.getResult()));
            m.put("rbi", ab.getRbi());
            m.put("finished", ab.getFinished());

            List<Map<String, Object>> pitches = new ArrayList<>();
            for (Pitch p : pitchRepo.findByAtBatIdOrderBySeqNoAsc(ab.getId())) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("seq", p.getSeqNo());
                pm.put("call", p.getCall().name());
                pm.put("callLabel", p.getCall().getLabel());
                pm.put("pitchType", orDash(p.getPitchType()));
                pm.put("speed", p.getSpeedKmh() == null ? "-" : p.getSpeedKmh() + " km/h");
                pm.put("count", p.getBallsAfter() + "-" + p.getStrikesAfter());
                pitches.add(pm);
            }
            m.put("pitchCount", pitches.size());
            m.put("pitches", pitches);
            atBats.add(m);

            if (ab.getResult() != null) {
                if (ab.getResult().isHit()) hits++;
                if (ab.getResult() == com.baseball.score.enums.PlayResult.WALK
                        || ab.getResult() == com.baseball.score.enums.PlayResult.HIT_BY_PITCH) walks++;
                if (ab.getResult() == com.baseball.score.enums.PlayResult.STRIKEOUT) strikeouts++;
                rbi += ab.getRbi();
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("player", playerBlock(lineup));
        root.put("teamName", lineup.getTeam().getName());
        root.put("teamSide", lineup.getTeamSide().name());
        root.put("summary", Map.of(
                "atBats", lineup.getAtBats(),
                "hits", lineup.getHits(),
                "rbi", Math.max(lineup.getRbi(), rbi),
                "walks", walks,
                "strikeouts", strikeouts,
                "today", lineup.getAtBats() + "-" + lineup.getHits()));
        root.put("atBats", atBats);
        return root;
    }

    private String resultColor(com.baseball.score.enums.PlayResult r) {
        if (r.isHit()) return "blue";
        if (r.getOuts() > 0) return "red";
        if (r == com.baseball.score.enums.PlayResult.WALK
                || r == com.baseball.score.enums.PlayResult.HIT_BY_PITCH) return "yellow";
        return "gray";
    }

    // ------------------------------------------------------------ 比賽清單

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listGames(String filter) {
        List<Game> games = "live".equalsIgnoreCase(filter)
                ? gameRepo.findByStatusOrderByGameDateDescIdDesc(GameStatus.LIVE)
                : "past".equalsIgnoreCase(filter)
                ? gameRepo.findByStatusOrderByGameDateDescIdDesc(GameStatus.FINISHED)
                : gameRepo.findAllByOrderByGameDateDescIdDesc();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Game g : games) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("status", g.getStatus().name());
            m.put("statusLabel", statusLabel(g.getStatus()));
            m.put("gameDate", g.getGameDate() == null ? "-" : g.getGameDate().format(DATE_FMT));
            m.put("awayName", g.getAwayTeam().getName());
            m.put("homeName", g.getHomeTeam().getName());
            m.put("awayScore", g.getAwayScore());
            m.put("homeScore", g.getHomeScore());
            m.put("inningLabel", g.getInning() + "局" + g.getHalf().getLabel());
            m.put("outs", g.getOuts());
            m.put("updatedAt", g.getUpdatedAt().format(TIME_FMT));
            m.put("winner", g.getStatus() == GameStatus.FINISHED
                    ? (g.getAwayScore() > g.getHomeScore() ? g.getAwayTeam().getName()
                    : g.getHomeScore() > g.getAwayScore() ? g.getHomeTeam().getName() : "平手")
                    : null);
            out.add(m);
        }
        return out;
    }

    private String statusLabel(GameStatus s) {
        return switch (s) { case LIVE -> "進行中"; case FINISHED -> "已結束"; case SCHEDULED -> "尚未開始"; };
    }

    private String orDash(String s) { return (s == null || s.isBlank()) ? "-" : s; }
}
