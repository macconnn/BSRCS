package com.baseball.score.controller;

import com.baseball.score.config.AuthInterceptor;
import com.baseball.score.config.CurrentUser;
import com.baseball.score.entity.Game;
import com.baseball.score.enums.DeviceType;
import com.baseball.score.repository.GameRepository;
import com.baseball.score.util.DeviceUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Optional;

/**
 * 畫面路由：
 *   /                 登入頁（可選擇以瀏覽者進入）
 *   /games            我的比賽 / 比賽列表
 *   /games/{id}       依「登入狀態 × 裝置」自動導到四張畫面之一
 *       登入 + PC     → editor-pc.html
 *       登入 + Mobile → editor-mobile.html
 *       未登入 + PC   → viewer-pc.html
 *       未登入 + Mobile → viewer-mobile.html
 *   加上 ?device=pc|mobile 可手動切換版面，?mode=viewer 可讓編輯者切到檢視模式
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final GameRepository gameRepo;

    @GetMapping("/")
    public String login(HttpServletRequest request, Model model) {
        CurrentUser user = currentUser(request);
        model.addAttribute("loggedIn", user.isLoggedIn());
        model.addAttribute("email", user.getEmail());
        return "login";
    }

    @GetMapping("/games")
    public String games(HttpServletRequest request, Model model) {
        CurrentUser user = currentUser(request);
        model.addAttribute("canEdit", user.canEdit());
        model.addAttribute("displayName", user.getDisplayName());
        return "games";
    }

    @GetMapping("/teams")
    public String teams(HttpServletRequest request, Model model) {
        CurrentUser user = currentUser(request);
        model.addAttribute("canEdit", user.canEdit());
        model.addAttribute("displayName", user.getDisplayName());
        return "teams";
    }

    @GetMapping("/teams/{id}")
    public String teamDetail(@PathVariable Long id, HttpServletRequest request, Model model) {
        CurrentUser user = currentUser(request);
        model.addAttribute("teamId", id);
        model.addAttribute("canEdit", user.canEdit());
        model.addAttribute("displayName", user.getDisplayName());
        return "team-detail";
    }

    @GetMapping("/games/{id}")
    public String game(@PathVariable Long id, HttpServletRequest request, Model model) {
        CurrentUser user = currentUser(request);
        DeviceType device = DeviceUtil.detect(request);
        boolean viewerOnly = "viewer".equalsIgnoreCase(request.getParameter("mode"));
        boolean canEdit = user.canEdit() && !viewerOnly;

        Optional<Game> game = gameRepo.findById(id);
        model.addAttribute("gameId", id);
        model.addAttribute("gameName", game.map(Game::getName).orElse("比賽"));
        model.addAttribute("canEdit", canEdit);
        model.addAttribute("loggedIn", user.isLoggedIn());
        model.addAttribute("device", device.name());
        model.addAttribute("displayName", user.getDisplayName());

        if (device == DeviceType.MOBILE) {
            return canEdit ? "editor-mobile" : "viewer-mobile";
        }
        return canEdit ? "editor-pc" : "viewer-pc";
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthInterceptor.ATTR_USER);
        return attr instanceof CurrentUser cu ? cu : CurrentUser.viewer();
    }
}
