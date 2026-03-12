/**
 * Firebase authentication filter for GeoQuest backend.
 * <p>
 * Verifies Firebase ID tokens from Authorization headers and sets authentication context.
 * <p>
 * Key features:
 * <ul>
 *   <li>Verifies Firebase ID tokens using Firebase Admin SDK</li>
 *   <li>Sets user authentication in SecurityContext</li>
 *   <li>Handles invalid/expired tokens with 401 response</li>
 * </ul>
 * <p>
 * Usage:
 * <ul>
 *   <li>Added to security filter chain before UsernamePasswordAuthenticationFilter.</li>
 *   <li>Used for stateless authentication of API requests.</li>
 * </ul>
 *
 * @author fl4nk3r
 */
package com.applabs.geo_quest.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        try {
            String idToken = header.substring(7);
            
            // Verify the Firebase ID token
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);
            String uid = decodedToken.getUid();
            String email = decodedToken.getEmail();

            System.out.println("Firebase token verified for user: " + email + " (uid: " + uid + ")");

            var auth = new UsernamePasswordAuthenticationToken(
                    uid, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (FirebaseAuthException e) {
            System.err.println("Firebase token verification failed: " + e.getMessage());
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or expired Firebase token\"}");
            return;
        } catch (Exception e) {
            System.err.println("Unexpected error during authentication: " + e.getMessage());
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication failed\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}