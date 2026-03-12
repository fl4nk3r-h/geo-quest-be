// PLAN: Riddle-based hints implementation
// - Authentication logic may need to be aware of hint changes for question progress
package com.applabs.geo_quest.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.applabs.geo_quest.security.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;

@RestController
@RequestMapping("/api/auth")
/**
 * Controller for authentication endpoints in GeoQuest.
 * <p>
 * Handles Google OAuth login and JWT token generation. Restricts access to
 * users with
 * 
 * @iiitkottayam.ac.in email domain. Delegates token verification to
 *                     GoogleIdTokenVerifier
 *                     and token creation to JwtUtil.
 *                     <p>
 *                     Endpoints:
 *                     <ul>
 *                     <li>POST /api/auth/google — Authenticate using Google
 *                     OAuth and receive JWT</li>
 *                     </ul>
 *                     <p>
 *                     Only users with valid Google tokens and allowed domain
 *                     can access the app.
 *
 * @author fl4nk3r
 */
public class AuthController {

    private final JwtUtil jwtUtil;
    private final GoogleIdTokenVerifier verifier; // inject the verifier bean
    private final String googleClientId;
    private static final String ALLOWED_DOMAIN = "iiitkottayam.ac.in";
    private static final boolean ENFORCE_DOMAIN_RESTRICTION = false; // TEMP: Set to false for testing
    // ← ADD verifier parameter to constructor

    public AuthController(JwtUtil jwtUtil,
            GoogleIdTokenVerifier verifier,
            @Value("${app.google.client-id}") String googleClientId) {
        this.jwtUtil = jwtUtil;
        this.verifier = verifier; // Spring injects the bean from GoogleAuthConfig
        this.googleClientId = googleClientId;
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> body) throws Exception {
        System.out.println("Received authentication request");
        String idToken = body.get("idToken");
        if (idToken == null || idToken.isBlank()) {
            System.err.println("No idToken proviif (ENFORCE_DOMAIN_RESTRICTION && (email == null || !email.endsWith(\"@\" + ALLOWED_DOMAIN))) {\n" + //
                                "    System.out.println(\"Domain restriction enforced - rejecting email: \" + email);\n" + //
                                "    return ResponseEntity.status(403).body(Map.of(\n" + //
                                "            \"error\", \"Access restricted to @\" + ALLOWED_DOMAIN + \" accounts\"));\n" + //
                                "}ded in request body");
            return ResponseEntity.badRequest().body(Map.of("error", "idToken is required"));
        }

        GoogleIdToken googleToken;
        try {
            googleToken = verifier.verify(idToken);
        } catch (Exception e) {
            System.err.println("Token verification failed: " + e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid Google token"));
        }

        if (googleToken == null) {
            System.err.println("Token verification returned null");
            return ResponseEntity.status(401).body(Map.of("error", "Invalid Google token"));
        }

        GoogleIdToken.Payload payload = googleToken.getPayload();
        String email = payload.getEmail();
        String uid = payload.getSubject();

        System.out.println("Token verified successfully for user: " + email);

        if (ENFORCE_DOMAIN_RESTRICTION && (email == null || !email.endsWith("@" + ALLOWED_DOMAIN))) {
            System.err.println("Domain restriction enforced - rejecting email: " + email);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Access restricted to @" + ALLOWED_DOMAIN + " accounts"));
        } else if (!ENFORCE_DOMAIN_RESTRICTION) {
            System.out.println("Domain restriction disabled - allowing all emails for testing");
        }

        String jwt = jwtUtil.generate(uid, email);
        System.out.println("JWT generated successfully for user: " + email);
        return ResponseEntity.ok(Map.of("token", jwt, "uid", uid, "email", email));
    }
}