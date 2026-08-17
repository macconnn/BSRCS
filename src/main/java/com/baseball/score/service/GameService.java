package com.baseball.score.service;

import com.baseball.score.dto.CreateGameRequest;
import com.baseball.score.entity.*;
import com.baseball.score.enums.GameStatus;
import com.baseball.score.enums.TeamSide;
import com.baseball.score.repository.*;
import com.baseball.score.util.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 比賽 / 打線的建立與維護 */
@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepo;
    private final TeamRepository teamRepo;
    private final PlayerRepository playerRepo;
    private final GameLineupRepository lineupRepo;
    private final GameEditorRepository gameEditorRepo;

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

        autoLineup(game, away, TeamSide.AWAY);
        autoLineup(game, home, TeamSide.HOME);
        return game;
    }

    /** 以球員預設守備位置自動排出 1~9 棒（之後可由編輯者調整） */
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
