package com.noq.security;

import com.noq.entity.StaffUser;
import com.noq.repository.StaffUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Tells Spring Security how to load a staff login from the staff_user table. */
@Service
public class StaffUserDetailsService implements UserDetailsService {

    private final StaffUserRepository staffUserRepository;

    public StaffUserDetailsService(StaffUserRepository staffUserRepository) {
        this.staffUserRepository = staffUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        StaffUser user = staffUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown staff user"));
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole().name()) // KITCHEN or STAFF -> authority ROLE_KITCHEN / ROLE_STAFF
                .disabled(!user.isActive())
                .build();
    }
}
