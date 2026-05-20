package com.costacloud.contractmanagement.service;

import com.costacloud.contractmanagement.dto.AuthRequest;
import com.costacloud.contractmanagement.dto.AuthResponse;
import com.costacloud.contractmanagement.model.User;
import com.costacloud.contractmanagement.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;


    public AuthService(UserRepository userRepository, JwtService jwtService, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthResponse authenticate(AuthRequest request) {
        Optional<User> existingUser = userRepository.findByEmail(request.getEmail());

        if (existingUser.isEmpty()) {
            // NEW USER — register them
            User newUser = new User();
            newUser.setEmail(request.getEmail());
            newUser.setPassword(passwordEncoder.encode(request.getPassword()));

            if ("admin@gmail.com".equals(request.getEmail())) {
                newUser.setRole(User.Role.ADMIN);
            } else {
                newUser.setRole(User.Role.USER);
            }

            userRepository.save(newUser);

            String token = jwtService.generateToken(newUser.getEmail(), newUser.getRole().name());
            return new AuthResponse(token, newUser.getEmail(), true, newUser.getRole().name());

        } else {
            // EXISTING USER — validate password
            User user = existingUser.get();
            boolean passwordMatch = passwordEncoder.matches(request.getPassword(), user.getPassword());

            if (!passwordMatch) {
                throw new RuntimeException("The password you entered is incorrect. Please try again.");
            }

            String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
            return new AuthResponse(token, user.getEmail(), false, user.getRole().name());
        }
    }
}
