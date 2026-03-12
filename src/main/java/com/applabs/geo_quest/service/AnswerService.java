/**
 * Service for validating and scoring answers in GeoQuest.
 * <p>
 * Handles answer submission, scoring, and hint logic.
 * <p>
 * Methods:
 * <ul>
 *   <li><b>submitAnswer</b>: Validates and scores an answer submission.</li>
 * </ul>
 * <p>
 * Usage:
 * <ul>
 *   <li>Used by controllers to process answer submissions and return results.</li>
 *   <li>Enforces proximity and attempt rules.</li>
 *   <li>Returns riddle/hint for next location on correct answer.</li>
 * </ul>
 *
 * @author fl4nk3r
 * @since 2026-03-11
 * @version 3.0
 */
package com.applabs.geo_quest.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.applabs.geo_quest.dto.request.AnswerSubmissionRequest;
import com.applabs.geo_quest.dto.response.AnswerResultResponse;
import com.applabs.geo_quest.enums.SessionStatus;
import com.applabs.geo_quest.exception.AccessDeniedException;
import com.applabs.geo_quest.exception.QuestionNotFoundException;
import com.applabs.geo_quest.exception.SessionNotFoundException;
import com.applabs.geo_quest.exception.TeamNotFoundException;
import com.applabs.geo_quest.model.Question;
import com.applabs.geo_quest.model.Session;
import com.applabs.geo_quest.model.Team;
import com.applabs.geo_quest.repository.QuestionRepository;
import com.applabs.geo_quest.repository.SessionRepository;
import com.applabs.geo_quest.repository.TeamRepository;

@Service
public class AnswerService {

    private static final int MAX_WRONG_ATTEMPTS = 2;
    private static final double GPS_TOLERANCE_METERS = 15.0;

    // After 1 wrong attempt the player earns only 3/5 of full points.
    // Numerator / denominator kept as constants so the rule is obvious.
    private static final double PENALTY_NUMERATOR = 3.0;
    private static final double PENALTY_DENOMINATOR = 5.0;

    private final SessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final TeamRepository teamRepository;
    private final SessionTimerService sessionTimerService;
    private final LeaderboardService leaderboardService;
    private final LocationService locationService;

    @Autowired
    public AnswerService(
            SessionRepository sessionRepository,
            QuestionRepository questionRepository,
            TeamRepository teamRepository,
            SessionTimerService sessionTimerService,
            LeaderboardService leaderboardService,
            LocationService locationService) {
        this.sessionRepository = sessionRepository;
        this.questionRepository = questionRepository;
        this.teamRepository = teamRepository;
        this.sessionTimerService = sessionTimerService;
        this.leaderboardService = leaderboardService;
        this.locationService = locationService;
    }

    /**
     * Validates and scores an answer submission.
     *
     * Rules enforced:
     * 1. Player must be physically within (unlockRadius + 15 m GPS tolerance)
     * of the question marker at submission time — closes the Hostel Exploit.
     * 2. After 1 wrong attempt → correct answer awards only 3/5 of full points.
     * 3. After 2 wrong attempts → question is permanently locked for this session.
     */
    @Transactional
    public AnswerResultResponse submitAnswer(AnswerSubmissionRequest request, String uid) {

        // ── 1. Load session ──────────────────────────────────────────────────
        Session session = sessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new SessionNotFoundException(request.getSessionId()));

        // ── 2. Auth first — team membership (V-05 fix) ──────────────────────
        Team team = teamRepository.findById(session.getTeamId())
                .orElseThrow(() -> new TeamNotFoundException(session.getTeamId()));

        if (!team.getMembers().contains(uid)) {
            throw new AccessDeniedException("You are not a member of this team");
        }

        // ── 3. Session must be active ────────────────────────────────────────
        if (!SessionStatus.ACTIVE.equals(session.getStatus())) {
            throw new IllegalStateException("Session is already completed");
        }

        // ── 4. Session must not have timed out ───────────────────────────────
        if (sessionTimerService.isSessionExpired(session)) {
            session.setStatus(SessionStatus.COMPLETED);
            sessionRepository.save(session);
            throw new IllegalStateException("Session has expired");
        }

        // ── 5. Already answered correctly? ───────────────────────────────────
        if (session.getAnsweredQuestionIds().contains(request.getQuestionId())) {
            return new AnswerResultResponse(
                    false,
                    "Already answered correctly",
                    0,
                    session.getScore(),
                    null,
                    null);
        }

        // ── 6. Load question ─────────────────────────────────────────────────
        Question question = questionRepository.findById(request.getQuestionId())
                .orElseThrow(() -> new QuestionNotFoundException(request.getQuestionId()));

        // ── 7. GPS proximity check — CLOSES THE HOSTEL EXPLOIT ───────────────
        double distanceMeters = locationService.distanceBetween(
                request.getUserLat(), request.getUserLng(),
                question.getLatitude(), question.getLongitude());

        double submissionRadius = question.getUnlockRadius() + GPS_TOLERANCE_METERS;

        if (distanceMeters > submissionRadius) {
            return new AnswerResultResponse(
                    false,
                    "You must be at the marker to answer. "
                            + "You are " + Math.round(distanceMeters) + " m away "
                            + "(must be within " + Math.round(submissionRadius) + " m).",
                    0,
                    session.getScore(),
                    null,
                    null);
        }

        // ── 8. Wrong-attempt gate ─────────────────────────────────────────────
        String questionId = request.getQuestionId();
        int wrongSoFar = session.getQuestionAttempts().getOrDefault(questionId, 0);

        if (wrongSoFar >= MAX_WRONG_ATTEMPTS) {
            return new AnswerResultResponse(
                    false,
                    "Question locked — " + MAX_WRONG_ATTEMPTS
                            + " wrong attempts already used for this question.",
                    0,
                    session.getScore(),
                    null,
                    null);
        }

        // ── 9. Evaluate the answer ───────────────────────────────────────────
        boolean correct = question.getCorrectAnswer() != null
                && question.getCorrectAnswer().trim()
                        .equalsIgnoreCase(request.getAnswer().trim());

        int pointsAwarded = 0;

        if (correct) {
            // ── 10. Calculate points (penalty if any prior wrong attempts) ────
            int fullPoints = question.getPoints();

            if (wrongSoFar == 0) {
                // No prior wrong attempts — full points
                pointsAwarded = fullPoints;
            } else {
                // 1 prior wrong attempt — 3/5 of full points
                pointsAwarded = (int) Math.round(
                        fullPoints * (PENALTY_NUMERATOR / PENALTY_DENOMINATOR));
            }

                // ── 11. Apply score and mark answered ─────────────────────────────
            session.setScore(session.getScore() + pointsAwarded);
            session.getAnsweredQuestionIds().add(questionId);

            sessionRepository.save(session);

                // ── 12. Sync leaderboard ──────────────────────────────────────────
            leaderboardService.updateScore(
                    session.getTeamId(), team.getTeamName(), session.getScore());

            // Find the next question assigned to this session that hasn't been answered yet
            String nextHint = null;
            for (String qid : session.getAssignedQuestionIds()) {
                if (!session.getAnsweredQuestionIds().contains(qid) && !qid.equals(questionId)) {
                    Question nextQ = questionRepository.findById(qid).orElse(null);
                    if (nextQ != null) {
                        nextHint = nextQ.getDescription();
                        break;
                    }
                }
            }

            String penaltyNote = wrongSoFar > 0
                    ? " (penalty applied: " + pointsAwarded + "/" + fullPoints + " pts)"
                    : "";

            return new AnswerResultResponse(
                    true,
                    "Correct! +" + pointsAwarded + " points" + penaltyNote,
                    pointsAwarded,
                    session.getScore(),
                    null,
                    nextHint);

        } else {
                // ── 13. Wrong answer — increment attempt counter ──────────────────
            int newWrongCount = wrongSoFar + 1;
            session.getQuestionAttempts().put(questionId, newWrongCount);
            
            int attemptsLeft = MAX_WRONG_ATTEMPTS - newWrongCount;
            String message;
            String nextHintForLocked = null;

            if (attemptsLeft <= 0) {
                // Question is now locked - mark as "answered" (with 0 points) so user moves to next
                session.getAnsweredQuestionIds().add(questionId);
                message = "Wrong answer. Question is now locked — moving to next question.";
                
                // Find the next question's hint
                for (String qid : session.getAssignedQuestionIds()) {
                    if (!session.getAnsweredQuestionIds().contains(qid)) {
                        Question nextQ = questionRepository.findById(qid).orElse(null);
                        if (nextQ != null) {
                            nextHintForLocked = nextQ.getDescription();
                            break;
                        }
                    }
                }
            } else {
                message = "Wrong answer. " + attemptsLeft + " attempt(s) remaining. "
                        + "Note: next correct answer will award 3/5 points.";
            }
            
            sessionRepository.save(session);

            return new AnswerResultResponse(
                    false,
                    message,
                    0,
                    session.getScore(),
                    null,
                    nextHintForLocked);
        }
    }
}