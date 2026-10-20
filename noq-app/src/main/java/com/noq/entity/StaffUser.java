package com.noq.entity;

import com.noq.enums.StaffRole;
import jakarta.persistence.*;

/** A canteen staff login. Passwords are stored only as BCrypt hashes. */
@Entity
@Table(name = "staff_user")
public class StaffUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)") // plain VARCHAR: no MySQL ENUM/CHECK, so a new enum value needs no ALTER TABLE
    private StaffRole role;

    @Column(nullable = false)
    private boolean active = true;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public StaffRole getRole() { return role; }
    public boolean isActive() { return active; }
}
