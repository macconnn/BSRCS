package com.baseball.score.controller;

import com.baseball.score.config.AuthInterceptor;
import com.baseball.score.config.CurrentUser;
import com.baseball.score.config.RequireEditor;
import com.baseball.score.dto.*;
import com.baseball.score.entity.Game;
import com.baseball.score.enums.TeamSide;
import com.baseball.score.repository.TeamRepository;
import com.baseball.score.service.GameQueryService;
import com.baseball.score.service.GameService;
import com.baseball.score.service.ScoringService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameApiController {

    private final GameQueryService queryService;
    private final GameService gameService;
    private final ScoringService scoringService;
    private final TeamRepository teamRepository;

    // ---------------------------------------------------------- 讀取（瀏覽者也可以）

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String filter) {
        return ApiResponse.ok(queryService.listGames(filter));
    }

    /** 前端每 10 秒輪詢一次 */
    @GetMapping("/{id}/state")
    public ApiResponse<Map<String, Object>> state(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(queryService.gameState(id, currentUser(request).canEdit()));
    }

    /** 球員本場表現（點擊打線姓名） */
    @GetMapping("/{id}/lineups/{lineupId}/log")
    public ApiResponse<Map<String, Object>> playerLog(@PathVariable Long id, @PathVariable Long lineupId) {
        return ApiResponse.ok(queryService.playerLog(id, lineupId));
    }

    @GetMapping("/teams")
    public ApiResponse<List<Map<String, Object>>> teams() {
        return ApiResponse.ok(teamRepository.findAll().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("color", t.getColorHex());
            return m;
        }).toList());
    }

    // ---------------------------------------------------------- 編輯（僅編輯者）

    @RequireEditor
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateGameRequest req, HttpServletRequest request) {
        Game game = gameService.createGame(req, currentUser(request).getUserId());
        return ApiResponse.ok("比賽已建立", Map.of("id", game.getId()));
    }

    @RequireEditor
    @PostMapping("/{id}/pitches")
    public ApiResponse<Map<String, Object>> pitch(@PathVariable Long id,
                                                  @Valid @RequestBody PitchRequest req,
                                                  HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.recordPitch(id, req.getCall(), req.getPitchType(), req.getSpeedKmh());
        return ApiResponse.ok("已記錄 " + req.getCall().getLabel(), queryService.gameState(id, true));
    }

    @RequireEditor
    @PostMapping("/{id}/results")
    public ApiResponse<Map<String, Object>> result(@PathVariable Long id,
                                                   @Valid @RequestBody ResultRequest req,
                                                   HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.recordResult(id, req.getResult());
        return ApiResponse.ok("已記錄 " + req.getResult().getLabel(), queryService.gameState(id, true));
    }

    /** 該隊目前可用來替補上場的球員（已經在場上的不會出現） */
    @RequireEditor
    @GetMapping("/{id}/bench")
    public ApiResponse<List<Map<String, Object>>> bench(@PathVariable Long id,
                                                          @RequestParam TeamSide side,
                                                          HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        return ApiResponse.ok(gameService.benchPlayers(id, side));
    }

    /** 換人：用板凳球員替補場上某位打線球員 */
    @RequireEditor
    @PostMapping("/{id}/substitutions")
    public ApiResponse<Map<String, Object>> substitute(@PathVariable Long id,
                                                        @Valid @RequestBody SubstitutionRequest req,
                                                        HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        gameService.substitute(id, req.getSide(), req.getOutLineupId(), req.getInPlayerId());
        return ApiResponse.ok("已完成換人", queryService.gameState(id, true));
    }

    /** 單純互換兩位場上球員的守備位置（不換人、不影響打線與棒次） */
    @RequireEditor
    @PostMapping("/{id}/position-swap")
    public ApiResponse<Map<String, Object>> swapPosition(@PathVariable Long id,
                                                          @Valid @RequestBody PositionSwapRequest req,
                                                          HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        gameService.swapPosition(id, req.getSide(), req.getLineupIdA(), req.getLineupIdB());
        return ApiResponse.ok("已互換守備位置", queryService.gameState(id, true));
    }

    @RequireEditor
    @PostMapping("/{id}/next-batter")
    public ApiResponse<Map<String, Object>> next(@PathVariable Long id, HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.nextBatter(id);
        return ApiResponse.ok("已換下一位打者", queryService.gameState(id, true));
    }

    @RequireEditor
    @PostMapping("/{id}/undo")
    public ApiResponse<Map<String, Object>> undo(@PathVariable Long id, HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.undo(id);
        return ApiResponse.ok("已復原上一個動作", queryService.gameState(id, true));
    }

    @RequireEditor
    @PostMapping("/{id}/reset")
    public ApiResponse<Map<String, Object>> reset(@PathVariable Long id, HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.reset(id);
        return ApiResponse.ok("比賽已重新開始", queryService.gameState(id, true));
    }

    @RequireEditor
    @PostMapping("/{id}/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long id, HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.start(id);
        return ApiResponse.ok("比賽開始", queryService.gameState(id, true));
    }

    @RequireEditor
    @PostMapping("/{id}/finish")
    public ApiResponse<Map<String, Object>> finish(@PathVariable Long id, HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.finish(id);
        return ApiResponse.ok("比賽已結束", queryService.gameState(id, true));
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthInterceptor.ATTR_USER);
        return attr instanceof CurrentUser cu ? cu : CurrentUser.viewer();
    }
}
