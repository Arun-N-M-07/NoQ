package com.noq.security;

import com.noq.entity.StaffUser;
import com.noq.enums.StaffRole;
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
        // An ADMIN can also do everything a STAFF member can.
        String[] roles = user.getRole() == StaffRole.ADMIN ? new String[]{"ADMIN", "STAFF"} : new String[]{"STAFF"};
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(roles)
                .disabled(!user.isActive())
                .build();
    }
}
