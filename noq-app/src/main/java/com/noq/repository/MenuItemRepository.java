package com.noq.repository;

import com.noq.entity.MenuItem;
import com.noq.enums.Category;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA writes the implementation of this interface at runtime.
 * JpaRepository already gives us save, findById, findAll, deleteById ... (CRUD).
 * The @Query strings are HQL/JPQL: they use ENTITY and FIELD names (MenuItem, popularityCount),
 * not table and column names (menu_item, popularity_count). Hibernate translates them to SQL.
 */
public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    /** SELECT ... FOR UPDATE: checkout locks the menu rows it is about to change, so buyers queue up instead of deadlocking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MenuItem> findWithLockById(Long id);

    @Query("select m from MenuItem m where m.active = true order by m.id")
    List<MenuItem> findAllActive();

    @Query("select m from MenuItem m where m.active = true and m.category = :category order by m.id")
    List<MenuItem> findActiveByCategory(@Param("category") Category category);

    /**
     * HQL QUERY 1 - most popular items that can be ordered right now, highest popularity first.
     * Ties (e.g. a brand-new canteen where every count is 0) fall back to the menu's curated id order.
     */
    @Query("""
            select m from MenuItem m
            where m.active = true and m.available = true and m.availableQuantity > 0
            order by m.popularityCount desc, m.id asc""")
    List<MenuItem> findPopularAvailable(Limit limit);
}
