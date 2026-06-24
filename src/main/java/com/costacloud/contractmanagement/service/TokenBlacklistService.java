package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.model.RevokedToken;
import com.costacloud.contractmanagement.repository.RevokedTokenRepository;
import org.springframework.stereotype.Service;

@Service
public class TokenBlacklistService {

    private final RevokedTokenRepository revokedTokenRepository;
    private final JwtService jwtService;

    public TokenBlacklistService(RevokedTokenRepository revokedTokenRepository,
                                 JwtService jwtService) {
        this.revokedTokenRepository = revokedTokenRepository;
        this.jwtService = jwtService;
    }

    public void revokeToken(String token) {
        RevokedToken revoked = new RevokedToken();
        revoked.setJti(jwtService.extractJti(token));
        revoked.setExpiresAt(jwtService.extractExpiration(token));
        revokedTokenRepository.save(revoked);
    }

    public boolean isRevoked(String token) {
        try {
            String jti = jwtService.extractJti(token);
            return revokedTokenRepository.existsByJti(jti);
        } catch (Exception e) {
            return false;
        }
    }
}
