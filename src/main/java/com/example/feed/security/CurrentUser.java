package com.example.feed.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    private final String userIdClaim;

    public CurrentUser(@org.springframework.beans.factory.annotation.Value(
            "${feed.security.jwt.user-id-claim:sub}") String userIdClaim) {
        this.userIdClaim = userIdClaim;
    }

    public long id(Jwt jwt) {
        Object value = "sub".equals(userIdClaim) ? jwt.getSubject() : jwt.getClaim(userIdClaim);
        return Long.parseLong(String.valueOf(value));
    }
}
