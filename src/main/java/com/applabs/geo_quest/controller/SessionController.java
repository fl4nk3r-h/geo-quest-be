
package com.applabs.geo_quest.controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.applabs.geo_quest.dto.request.StartSessionRequest;
import com.applabs.geo_quest.dto.response.CurrentQuestionResponse;
import com.applabs.geo_quest.dto.response.RemainingTimeResponse;
import com.applabs.geo_quest.enums.SessionStatus;
import com.applabs.geo_quest.exception.AccessDeniedException;
import com.applabs.geo_quest.exception.SessionNotFoundException;
import com.applabs.geo_quest.exception.TeamNotFoundException;
import com.applabs.geo_quest.model.Session;
import com.applabs.geo_quest.model.Team;
import com.applabs.geo_quest.model.Question;
import com.applabs.geo_quest.repository.QuestionRepository;
import com.applabs.geo_quest.repository.SessionRepository;
import com.applabs.geo_quest.repository.TeamRepository;
import com.applabs.geo_quest.service.LocationService;
import com.applabs.geo_quest.service.SessionTimerService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sessions")
/**
 * Controller for session management endpoints in GeoQuest.
 * <p>
 * Handles starting, retrieving, and timing of competition sessions for teams.
 * Enforces
 * session rules, assigns questions, and manages session lifecycle. Delegates
 * persistence
 * and timing logic to SessionRepository, TeamRepository, and
 * SessionTimerService.
 * <p>
 * Endpoints:
 * <ul>
 * <li>POST /api/sessions/start — Start a new session for a team</li>
 * <li>GET /api/sessions/{sessionId} — Get session details</li>
 * <li>GET /api/sessions/{sessionId}/remaining-time — Get session remaining
 * time</li>
 * </ul>
 * <p>
 * Enforces access control for session actions and auto-completes expired
 * sessions.
 *
 * @author fl4nk3r
 */
public class SessionController {

    private final SessionRepository sessionRepository;
    private final TeamRepository teamRepository;
    private final SessionTimerService sessionTimerService;
    private final QuestionRepository questionRepository;
    private final LocationService locationService;

    @Autowired
    public SessionController(SessionRepository sessionRepository,
            TeamRepository teamRepository,
            SessionTimerService sessionTimerService,
            QuestionRepository questionRepository,
            LocationService locationService) {
        this.sessionRepository = sessionRepository;
        this.teamRepository = teamRepository;
        this.sessionTimerService = sessionTimerService;
        this.questionRepository = questionRepository;
        this.locationService = locationService;
    }

    /**
     * POST /api/sessions/start
     * Starts a 2-hour competition session for a team.
     * Only one active session per team is allowed.
     */
    @PostMapping("/start")
    public ResponseEntity<Session> startSession(
            @Valid @RequestBody StartSessionRequest request,
            @AuthenticationPrincipal String uid) {

        Team team = teamRepository.findById(request.getTeamId())
                .orElseThrow(() -> new TeamNotFoundException(request.getTeamId()));

        // Only team members can start a session
        if (!team.getMembers().contains(uid)) {
            throw new AccessDeniedException("You are not a member of this team");
        }

        // End any existing active session before starting a new one
        sessionRepository.findByTeamIdAndStatus(request.getTeamId(), SessionStatus.ACTIVE)
                .ifPresent(existingSession -> {
                    existingSession.setStatus(SessionStatus.COMPLETED);
                    existingSession.setEndTime(Instant.now());
                    sessionRepository.save(existingSession);
                });

        // ── Assign a uniquely-shuffled question list to this session ─────────
        // Each location has 2 questions per difficulty tier. We shuffle within
        // each tier and pick ONE question per location — the first encountered
        // in the shuffled order. Two teams at the same marker get a different
        // question because their shuffles are independent. LocationService then
        // filters to only the questions present in this list, so the alternate
        // is never surfaced to this team.
        List<String> assigned = new ArrayList<>();
        for (int diff = 1; diff <= 3; diff++) {
            List<com.applabs.geo_quest.model.Question> tierQuestions = questionRepository.findByDifficulty(diff);
            Collections.shuffle(tierQuestions);
            java.util.Set<String> seenLocations = new java.util.LinkedHashSet<>();
            for (com.applabs.geo_quest.model.Question q : tierQuestions) {
                if (seenLocations.add(q.getLocationName())) {
                    assigned.add(q.getQuestionId());
                }
            }
        }

        Instant start = Instant.now();
        Session session = Session.builder()
                .teamId(request.getTeamId())
                .uid(uid)
                .startTime(start)
                .endTime(sessionTimerService.computeEndTime(start))
                .score(0)
                .status(SessionStatus.ACTIVE)
                .assignedQuestionIds(assigned)
                .build();

        return ResponseEntity.ok(sessionRepository.save(session));
    }

    /**
     * GET /api/sessions/{sessionId}
     * Returns session details. Only team members can view it.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Session> getSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String uid) {

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        Team team = teamRepository.findById(session.getTeamId())
                .orElseThrow(() -> new TeamNotFoundException(session.getTeamId()));

        if (!team.getMembers().contains(uid)) {
            throw new AccessDeniedException("You are not a member of this session's team");
        }

        // Auto-complete expired sessions on read
        if (SessionStatus.ACTIVE.equals(session.getStatus()) && sessionTimerService.isSessionExpired(session)) {
            session.setStatus(SessionStatus.COMPLETED);
            sessionRepository.save(session);
        }

        return ResponseEntity.ok(session);
    }

    /**
     * GET /api/sessions/{sessionId}/current-question
     * Returns the current (next unanswered) question for the session.
     * Includes coordinates for proximity tracking.
     * 
     * Query params:
     * - userLat: User's current latitude
     * - userLng: User's current longitude
     */
    @GetMapping("/{sessionId}/current-question")
    public ResponseEntity<CurrentQuestionResponse> getCurrentQuestion(
            @PathVariable String sessionId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Double userLat,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Double userLng,
            @AuthenticationPrincipal String uid) {

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        Team team = teamRepository.findById(session.getTeamId())
                .orElseThrow(() -> new TeamNotFoundException(session.getTeamId()));

        if (!team.getMembers().contains(uid)) {
            throw new AccessDeniedException("You are not a member of this session's team");
        }

        // Check if session is still active
        if (!SessionStatus.ACTIVE.equals(session.getStatus())) {
            throw new IllegalStateException("Session is not active");
        }

        if (sessionTimerService.isSessionExpired(session)) {
            session.setStatus(SessionStatus.COMPLETED);
            sessionRepository.save(session);
            throw new IllegalStateException("Session has expired");
        }

        // Find the first unanswered question in the assigned list
        for (String questionId : session.getAssignedQuestionIds()) {
            if (!session.getAnsweredQuestionIds().contains(questionId)) {
                Question q = questionRepository.findById(questionId).orElse(null);
                if (q != null) {
                    // Check if user is within range
                    boolean withinRange = false;
                    if (userLat != null && userLng != null) {
                        double distance = locationService.distanceBetween(
                                userLat, userLng, q.getLatitude(), q.getLongitude());
                        withinRange = distance <= q.getUnlockRadius() + 15.0; // 15m GPS tolerance
                    }

                    CurrentQuestionResponse response = CurrentQuestionResponse.builder()
                            .questionId(q.getQuestionId())
                            .title(q.getTitle())
                            .description(q.getDescription())
                            .difficulty(q.getDifficulty())
                            .points(q.getPoints())
                            .category(q.getCategory())
                            .options(q.getOptions())
                            .locationName(q.getLocationName())
                            .latitude(q.getLatitude())
                            .longitude(q.getLongitude())
                            .unlockRadius(q.getUnlockRadius())
                            .questionsAnswered(session.getAnsweredQuestionIds().size())
                            .totalQuestions(session.getAssignedQuestionIds().size())
                            .withinRange(withinRange)
                            .build();
                    return ResponseEntity.ok(response);
                }
            }
        }

        // All questions answered
        return ResponseEntity.notFound().build();
    }

    /**
     * GET /api/sessions/team/{teamId}/active
     * Returns the active session for a team, or 404 if none exists.
     */
    @GetMapping("/team/{teamId}/active")
    public ResponseEntity<Session> getActiveSession(
            @PathVariable String teamId,
            @AuthenticationPrincipal String uid) {

        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));

        if (!team.getMembers().contains(uid)) {
            throw new AccessDeniedException("You are not a member of this team");
        }

        return sessionRepository.findByTeamIdAndStatus(teamId, SessionStatus.ACTIVE)
                .map(session -> {
                    // Auto-complete expired sessions
                    if (sessionTimerService.isSessionExpired(session)) {
                        session.setStatus(SessionStatus.COMPLETED);
                        sessionRepository.save(session);
                        return ResponseEntity.notFound().<Session>build();
                    }
                    return ResponseEntity.ok(session);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/sessions/{sessionId}/end
     * Manually ends an active session.
     */
    @PostMapping("/{sessionId}/end")
    public ResponseEntity<Session> endSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String uid) {

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        Team team = teamRepository.findById(session.getTeamId())
                .orElseThrow(() -> new TeamNotFoundException(session.getTeamId()));

        if (!team.getMembers().contains(uid)) {
            throw new AccessDeniedException("You are not a member of this session's team");
        }

        if (!SessionStatus.ACTIVE.equals(session.getStatus())) {
            throw new IllegalStateException("Session is not active");
        }

        session.setStatus(SessionStatus.COMPLETED);
        session.setEndTime(java.time.Instant.now());
        return ResponseEntity.ok(sessionRepository.save(session));
    }

    /**
     * GET /api/sessions/{sessionId}/remaining-time
     * Returns remaining seconds and whether the session is still active.
     */
    @GetMapping("/{sessionId}/remaining-time")
    public ResponseEntity<RemainingTimeResponse> getRemainingTime(
            @PathVariable String sessionId,
            @AuthenticationPrincipal String uid) {

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        long remaining = sessionTimerService.getRemainingSeconds(session);
        boolean active = SessionStatus.ACTIVE.equals(session.getStatus()) && remaining > 0;

        return ResponseEntity.ok(new RemainingTimeResponse(remaining, active));
    }
}