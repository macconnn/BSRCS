package com.baseball.score.controller;

import com.baseball.score.config.AuthInterceptor;
import com.baseball.score.config.CurrentUser;
import com.baseball.score.config.RequireEditor;
import com.baseball.score.dto.ApiResponse;
import com.baseball.score.dto.PlayerRequest;
import com.baseball.score.dto.TeamRequest;
import com.baseball.score.entity.Player;
import com.baseball.score.entity.Team;
import com.baseball.score.service.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TeamApiController {

    private final TeamService teamService;

    // ---------------------------------------------------------- 讀取（瀏覽者也可以）

    @GetMapping("/teams")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(teamService.listTeams());
    }

    @GetMapping("/teams/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(teamService.teamDetail(id));
    }

    // ---------------------------------------------------------- 編輯（僅編輯者）

    @RequireEditor
    @PostMapping("/teams")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody TeamRequest req, HttpServletRequest request) {
        Team team = teamService.createTeam(req, currentUser(request).getUserId());
        return ApiResponse.ok("球隊已建立", Map.of("id", team.getId()));
    }

    @RequireEditor
    @PutMapping("/teams/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @Valid @RequestBody TeamRequest req) {
        Team team = teamService.updateTeam(id, req);
        return ApiResponse.ok("球隊資料已更新", Map.of("id", team.getId()));
    }

    @RequireEditor
    @PostMapping("/teams/{id}/players")
    public ApiResponse<Map<String, Object>> addPlayer(@PathVariable Long id, @Valid @RequestBody PlayerRequest req) {
        Player p = teamService.addPlayer(id, req);
        return ApiResponse.ok("已新增球員 " + p.getName(), Map.of("id", p.getId()));
    }

    @RequireEditor
    @PutMapping("/players/{playerId}")
    public ApiResponse<Map<String, Object>> updatePlayer(@PathVariable Long playerId, @Valid @RequestBody PlayerRequest req) {
        Player p = teamService.updatePlayer(playerId, req);
        return ApiResponse.ok("已更新球員 " + p.getName(), Map.of("id", p.getId()));
    }

    @RequireEditor
    @DeleteMapping("/players/{playerId}")
    public ApiResponse<Void> removePlayer(@PathVariable Long playerId) {
        teamService.removePlayer(playerId);
        return ApiResponse.ok("已移除球員", null);
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthInterceptor.ATTR_USER);
        return attr instanceof CurrentUser cu ? cu : CurrentUser.viewer();
    }
}
