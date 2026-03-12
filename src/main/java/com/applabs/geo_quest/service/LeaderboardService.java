/**
 * Service for managing the GeoQuest leaderboard.
 * <p>
 * Handles score updates and retrieval of team rankings.
 * <p>
 * Methods:
 * <ul>
 *   <li><b>getLeaderboard</b>: Returns all leaderboard entries sorted by score.</li>
 *   <li><b>updateScore</b>: Upserts the score for a team.</li>
 * </ul>
 * <p>
 * Usage:
 * <ul>
 *   <li>Used by controllers/services to display and update team rankings.</li>
 * </ul>
 *
 * @author fl4nk3r
 * @since 2026-03-11
 * @version 3.0
 */
package com.applabs.geo_quest.service;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.applabs.geo_quest.model.Leaderboard;
import com.applabs.geo_quest.repository.LeaderboardRepository;

@Service
public class LeaderboardService {

    private final LeaderboardRepository leaderboardRepository;

    @Autowired
    public LeaderboardService(LeaderboardRepository leaderboardRepository) {
        this.leaderboardRepository = leaderboardRepository;
    }

    /** Returns all entries sorted by score descending. */
    public List<Leaderboard> getLeaderboard() {
        return leaderboardRepository.findAllByOrderByScoreDesc();
    }

    /**
     * Upserts the score for a team.
     * Called automatically after every correct answer.
     */
    public void updateScore(String teamId, String teamName, int newScore) {
        Leaderboard entry = leaderboardRepository.findById(teamId)
                .orElseGet(() -> Leaderboard.builder()
                        .teamId(teamId)
                        .teamName(teamName)
                        .build());

        entry.setScore(newScore);
        entry.setLastUpdated(Instant.now());
        leaderboardRepository.save(entry);
    }

    /**
     * Clears the score for a team from the leaderboard.
     * Used when restarting a game (debug).
     */
    public void clearScore(String teamId) {
        leaderboardRepository.deleteById(teamId);
    }
}
