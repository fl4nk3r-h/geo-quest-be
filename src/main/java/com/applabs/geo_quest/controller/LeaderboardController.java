// PLAN: Riddle-based hints implementation
// - Leaderboard logic may need to be aware of hint changes for question progress
package com.applabs.geo_quest.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.applabs.geo_quest.model.Leaderboard;
import com.applabs.geo_quest.service.LeaderboardService;

@RestController
@RequestMapping("/api/leaderboard")
/**
 * Controller for leaderboard endpoints in GeoQuest.
 * <p>
 * Provides public access to the leaderboard, listing all teams sorted by score.
 * Delegates leaderboard retrieval to LeaderboardService.
 * <p>
 * Endpoints:
 * <ul>
 * <li>GET /api/leaderboard — Retrieve leaderboard (public)</li>
 * </ul>
 * <p>
 * No authentication required for leaderboard access.
 *
 * @author fl4nk3r
 */
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @Autowired
    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    /**
     * GET /api/leaderboard
     * Returns all teams sorted by score descending.
     * This endpoint is public — no auth token required.
     */
    @GetMapping
    public ResponseEntity<List<Leaderboard>> getLeaderboard() {
        return ResponseEntity.ok(leaderboardService.getLeaderboard());
    }

    /**
     * DELETE /api/leaderboard/{teamId}
     * Clears the score for a team (used for debug restart).
     */
    @DeleteMapping("/{teamId}")
    public ResponseEntity<Void> clearScore(@PathVariable String teamId) {
        leaderboardService.clearScore(teamId);
        return ResponseEntity.noContent().build();
    }
}
