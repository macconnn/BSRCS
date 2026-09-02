package com.baseball.score.controller;

import com.baseball.score.config.AuthInterceptor;
import com.baseball.score.config.CurrentUser;
import com.baseball.score.config.RequireAdmin;
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

    /** 刪除整場比賽紀錄（含所有打席、投球、比分等關聯資料），操作無法復原；僅限管理員 */
    @RequireAdmin
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        gameService.deleteGame(id);
        return ApiResponse.ok("比賽紀錄已刪除", null);
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

    /** 盜壘：獨立於打席結果之外的跑者事件，不影響打者打數、不結束打席 */
    @RequireEditor
    @PostMapping("/{id}/steal")
    public ApiResponse<Map<String, Object>> steal(@PathVariable Long id,
                                                   @Valid @RequestBody StealRequest req,
                                                   HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.recordSteal(id, req.getFromBase(), req.getToBase(), req.getOutcome(), req.isError());
        return ApiResponse.ok("已記錄盜壘", queryService.gameState(id, true));
    }

    /** 加碼失誤推進：安打／出局結果之後，因守備失誤造成的額外壘包推進（不算安打、不算打點，但算一次球隊失誤） */
    @RequireEditor
    @PostMapping("/{id}/error-advance")
    public ApiResponse<Map<String, Object>> errorAdvance(@PathVariable Long id,
                                                          @Valid @RequestBody ErrorAdvanceRequest req,
                                                          HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.recordErrorAdvance(id, req.getFromBase(), req.getToBase());
        return ApiResponse.ok("已記錄失誤推進", queryService.gameState(id, true));
    }

    /** 手動編輯壘包狀態：處理現有規則涵蓋不到的特殊狀況 */
    @RequireEditor
    @PostMapping("/{id}/bases")
    public ApiResponse<Map<String, Object>> editBases(@PathVariable Long id,
                                                       @Valid @RequestBody BaseEditRequest req,
                                                       HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.editBases(id, req.getRunnerFirst(), req.getRunnerSecond(), req.getRunnerThird());
        return ApiResponse.ok("已更新壘包狀態", queryService.gameState(id, true));
    }

    /** 手動編輯某一局的得分：修正手誤，並自動同步重算該隊總分，確保總分與每局分數對得起來 */
    @RequireEditor
    @PostMapping("/{id}/score")
    public ApiResponse<Map<String, Object>> editScore(@PathVariable Long id,
                                                       @Valid @RequestBody ScoreEditRequest req,
                                                       HttpServletRequest request) {
        gameService.assertCanEditGame(id, currentUser(request).getUserId());
        scoringService.editInningScore(id, req.getSide(), req.getInning(), req.getRuns());
        return ApiResponse.ok("已更新比分", queryService.gameState(id, true));
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
