package com.baseball.score.service;

import com.baseball.score.config.AppProperties;
import com.baseball.score.entity.*;
import com.baseball.score.enums.GameStatus;
import com.baseball.score.enums.TeamSide;
import com.baseball.score.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 啟動時建立示範資料（藍隊 vs 紅隊，春季聯賽例行賽），app.seed=false 可關閉。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final AppProperties props;
    private final TeamRepository teamRepo;
    private final PlayerRepository playerRepo;
    private final GameRepository gameRepo;
    private final GameLineupRepository lineupRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.isSeed() || gameRepo.count() > 0) return;

        Team blue = teamRepo.save(Team.builder().name("藍隊").shortName("藍").colorHex("#1d4ed8").build());
        Team red = teamRepo.save(Team.builder().name("紅隊").shortName("紅").colorHex("#dc2626").build());

        // 打擊率不再於建立球員時寫死帶入，一律從 0 開始，之後由實際打擊紀錄動態算出
        List<Player> bluePlayers = savePlayers(blue, new String[][]{
                {"王小明", "18", "中外野手"},
                {"李大同", "7", "二壘手"},
                {"張志強", "23", "一壘手"},
                {"陳建宏", "55", "指定打擊"},
                {"林志豪", "6", "三壘手"},
                {"黃文彬", "12", "右外野手"},
                {"劉家豪", "34", "捕手"},
                {"周子揚", "9", "左外野手"},
                {"吳明軒", "5", "游擊手"},
                {"沈柏宇", "21", "投手"}
        });
        List<Player> redPlayers = savePlayers(red, new String[][]{
                {"陳冠宇", "8", "中外野手"},
                {"林立", "2", "游擊手"},
                {"張育成", "3", "一壘手"},
                {"林安可", "18", "右外野手"},
                {"朱育賢", "5", "指定打擊"},
                {"廖健富", "16", "投手"},
                {"郭天信", "7", "左外野手"},
                {"戴培峰", "27", "捕手"},
                {"李凱威", "9", "二壘手"},
                {"朱育賢二", "35", "三壘手"}
        });

        Game game = gameRepo.save(Game.builder()
                .name("春季聯賽例行賽")
                .gameDate(LocalDate.of(2024, 5, 25))
                .venue("市立棒球場")
                .awayTeam(blue).homeTeam(red)
                .status(GameStatus.LIVE)
                .totalInnings(9)
                .build());

        List<GameLineup> blueLineup = saveLineup(game, blue, bluePlayers, TeamSide.AWAY);
        List<GameLineup> redLineup = saveLineup(game, red, redPlayers, TeamSide.HOME);

        // 紅隊守備中，投手 = 廖健富
        redLineup.stream().filter(l -> "投手".equals(l.getPosition())).findFirst()
                .ifPresent(l -> game.setHomePitcherLineupId(l.getId()));
        blueLineup.stream().filter(l -> "投手".equals(l.getPosition())).findFirst()
                .ifPresent(l -> game.setAwayPitcherLineupId(l.getId()));
        gameRepo.save(game);

        log.info("示範資料已建立：比賽 id={}（{} vs {}）", game.getId(), blue.getName(), red.getName());
    }

    private List<Player> savePlayers(Team team, String[][] rows) {
        List<Player> list = new ArrayList<>();
        for (String[] r : rows) {
            list.add(playerRepo.save(Player.builder()
                    .team(team).name(r[0]).jerseyNumber(r[1]).defaultPosition(r[2])
                    .active(true)
                    .build()));
        }
        return list;
    }

    private List<GameLineup> saveLineup(Game game, Team team, List<Player> players, TeamSide side) {
        List<GameLineup> list = new ArrayList<>();
        int order = 1;
        for (Player p : players) {
            if (order > 9) break;
            list.add(lineupRepo.save(GameLineup.builder()
                    .game(game).team(team).player(p).teamSide(side)
                    .battingOrder(order++).position(p.getDefaultPosition())
                    .starter(true).active(true)
                    .build()));
        }
        return list;
    }
}
