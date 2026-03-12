package com.applabs.geo_quest.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import jakarta.annotation.PostConstruct;

/**
 * Configuration class for Firebase Admin SDK.
 * <p>
 * Initializes Firebase Admin SDK for verifying Firebase ID tokens
 * from the frontend React Native app.
 * <p>
 * Firebase manages user sessions - the backend only verifies tokens.
 *
 * @author fl4nk3r
 */
@Configuration
public class FirebaseConfig {

    // TODO: Set FIREBASE_CREDENTIALS environment variable with your Firebase service account JSON
    // Get this from: Firebase Console → Project Settings → Service accounts → Generate new private key
    @Value("${FIREBASE_CREDENTIALS:}")
    private String firebaseCredentials;

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseOptions options;
                
                if (firebaseCredentials != null && !firebaseCredentials.isBlank()) {
                    // Use credentials from environment variable
                    InputStream serviceAccount = new ByteArrayInputStream(
                        firebaseCredentials.getBytes(StandardCharsets.UTF_8)
                    );
                    options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();
                    System.out.println("Firebase initialized with service account credentials");
                } else {
                    // Use Application Default Credentials for local development
                    options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.getApplicationDefault())
                        .build();
                    System.out.println("Firebase initialized with Application Default Credentials");
                }
                
                FirebaseApp.initializeApp(options);
                System.out.println("Firebase Admin SDK initialized successfully");
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize Firebase: " + e.getMessage());
            throw new RuntimeException("Failed to initialize Firebase Admin SDK", e);
        }
    }
}
