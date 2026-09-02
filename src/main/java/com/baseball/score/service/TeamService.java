package com.baseball.score.service;

import com.baseball.score.dto.PlayerRequest;
import com.baseball.score.dto.TeamRequest;
import com.baseball.score.entity.Player;
import com.baseball.score.entity.Team;
import com.baseball.score.repository.GameRepository;
import com.baseball.score.repository.PlayerRepository;
import com.baseball.score.repository.TeamRepository;
import com.baseball.score.util.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 球隊管理：球隊 CRUD、球員（背號 / 姓名 / 守備位置）維護；打擊率改由 PlayerStatsService 動態算出 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepo;
    private final PlayerRepository playerRepo;
    private final GameRepository gameRepo;
    private final PlayerStatsService statsService;
    private final PitcherStatsService pitcherStatsService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTeams() {
        return teamRepo.findAll().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("shortName", t.getShortName() == null ? "" : t.getShortName());
            m.put("color", t.getColorHex() == null ? "#1d4ed8" : t.getColorHex());
            m.put("playerCount", playerRepo.findByTeamIdAndActiveTrueOrderByIdAsc(t.getId()).size());
            return m;
        }).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> teamDetail(Long teamId) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到球隊"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", team.getId());
        m.put("name", team.getName());
        m.put("shortName", team.getShortName() == null ? "" : team.getShortName());
        m.put("color", team.getColorHex() == null ? "#1d4ed8" : team.getColorHex());
        m.put("players", playerRepo.findByTeamIdAndActiveTrueOrderByIdAsc(teamId).stream().map(p -> {
            PlayerStatsService.CareerStats stats = statsService.careerStats(p.getId());
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.getId());
            pm.put("name", p.getName());
            pm.put("jerseyNumber", p.getJerseyNumber() == null ? "" : p.getJerseyNumber());
            pm.put("position", p.getDefaultPosition() == null ? "" : p.getDefaultPosition());
            // 動態打擊率：依這位球員所有已記錄的打席（安打/出局/保送...）即時算出，非手動輸入
            pm.put("battingAvg", statsService.avgText(stats.avg()));
            pm.put("battingAvgValue", stats.avg());
            pm.put("atBats", stats.atBats());
            pm.put("hits", stats.hits());
            return pm;
        }).toList());
        return m;
    }

    /**
     * 單一球員的完整數據總覽：打擊數據一定有；投手數據只有在這位球員「曾經被記錄過投手數據」
     * （game_pitcher_stat 有任何一列）時才會回傳，否則是 null——由前端依此決定要不要顯示「投手數據」分頁。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> playerStats(Long playerId) {
        Player player = playerRepo.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到球員"));

        PlayerStatsService.CareerStats b = statsService.careerStats(playerId);
        Map<String, Object> batting = new LinkedHashMap<>();
        batting.put("pa", b.pa());
        batting.put("atBats", b.atBats());
        batting.put("hits", b.hits());
        batting.put("doubles", b.doubles());
        batting.put("triples", b.triples());
        batting.put("homeRuns", b.homeRuns());
        batting.put("walks", b.walks());
        batting.put("strikeouts", b.strikeouts());
        batting.put("sacBunts", b.sacBunts());
        batting.put("stolenBases", b.stolenBases());
        batting.put("avg", statsService.avgText(b.avg()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", player.getId());
        m.put("name", player.getName());
        m.put("jerseyNumber", player.getJerseyNumber() == null ? "" : player.getJerseyNumber());
        m.put("position", player.getDefaultPosition() == null ? "" : player.getDefaultPosition());
        m.put("teamName", player.getTeam().getName());
        m.put("batting", batting);

        if (pitcherStatsService.hasPitchingRecord(playerId)) {
            PitcherStatsService.CareerPitchingStats p = pitcherStatsService.careerStats(playerId);
            Map<String, Object> pitching = new LinkedHashMap<>();
            pitching.put("gamesPitched", p.gamesPitched());
            pitching.put("inningsPitched", pitcherStatsService.inningsText(p.inningsOuts()));
            pitching.put("pitches", p.pitches());
            pitching.put("runsAllowed", p.runsAllowed());
            pitching.put("hitsAllowed", p.hitsAllowed());
            pitching.put("doublesAllowed", p.doublesAllowed());
            pitching.put("triplesAllowed", p.triplesAllowed());
            pitching.put("homeRunsAllowed", p.homeRunsAllowed());
            pitching.put("walksAllowed", p.walksAllowed());
            pitching.put("hitByPitchAllowed", p.hitByPitchAllowed());
            pitching.put("stolenBasesAllowed", p.stolenBasesAllowed());
            pitching.put("era", pitcherStatsService.eraText(p.era()));
            m.put("pitching", pitching);
        } else {
            m.put("pitching", null);
        }
        return m;
    }

    @Transactional
    public Team createTeam(TeamRequest req, Long userId) {
        teamRepo.findByName(req.getName().trim()).ifPresent(t -> {
            throw new ApiException("已經有同名球隊：" + t.getName());
        });
        return teamRepo.save(Team.builder()
                .name(req.getName().trim())
                .shortName(blankToNull(req.getShortName()))
                .colorHex(blankToNull(req.getColorHex()))
                .ownerUserId(userId)
                .build());
    }

    @Transactional
    public Team updateTeam(Long teamId, TeamRequest req) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到球隊"));
        team.setName(req.getName().trim());
        team.setShortName(blankToNull(req.getShortName()));
        team.setColorHex(blankToNull(req.getColorHex()));
        return teamRepo.save(team);
    }

    @Transactional
    public Player addPlayer(Long teamId, PlayerRequest req) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到球隊"));
        // 新球員一律從沒有打擊紀錄開始，打擊率動態顯示為 0（.000），直到實際上場累積打席
        return playerRepo.save(Player.builder()
                .team(team)
                .name(req.getName().trim())
                .jerseyNumber(blankToNull(req.getJerseyNumber()))
                .defaultPosition(blankToNull(req.getDefaultPosition()))
                .active(true)
                .build());
    }

    @Transactional
    public Player updatePlayer(Long playerId, PlayerRequest req) {
        Player player = playerRepo.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到球員"));
        player.setName(req.getName().trim());
        player.setJerseyNumber(blankToNull(req.getJerseyNumber()));
        player.setDefaultPosition(blankToNull(req.getDefaultPosition()));
        return playerRepo.save(player);
    }

    /** 軟刪除：保留歷史比賽紀錄，只是不再出現在名單 */
    @Transactional
    public void removePlayer(Long playerId) {
        Player player = playerRepo.findById(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到球員"));
        player.setActive(false);
        playerRepo.save(player);
    }

    /** 刪除整支球隊（含旗下球員）。若球隊已經有比賽紀錄（不論主客場）則禁止刪除，避免留下對應不到球隊的比賽資料 */
    @Transactional
    public void deleteTeam(Long teamId) {
        Team team = teamRepo.findById(teamId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "找不到球隊"));

        if (gameRepo.existsByAwayTeamIdOrHomeTeamId(teamId, teamId)) {
            throw new ApiException("此球隊已有比賽紀錄，無法刪除；請先刪除相關比賽紀錄");
        }

        playerRepo.deleteByTeamId(teamId);
        teamRepo.delete(team);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
