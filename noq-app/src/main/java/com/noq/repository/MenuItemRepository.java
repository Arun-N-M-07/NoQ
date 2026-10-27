package com.noq.repository;

import com.noq.entity.MenuItem;
import com.noq.enums.Category;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Spring Data JPA writes the implementation of this interface at runtime.
 * JpaRepository already gives us save, findById, findAll, deleteById ... (CRUD).
 * The @Query strings are HQL/JPQL: they use ENTITY and FIELD names (MenuItem, popularityCount),
 * not table and column names (menu_item, popularity_count). Hibernate translates them to SQL.
 */
public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

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
            where m.active = true and m.available = true
            order by m.popularityCount desc, m.id asc""")
    List<MenuItem> findPopularAvailable(Limit limit);

    /**
     * Adds delta (negative on cancellation, never below 0) to the popularity count in ONE atomic UPDATE,
     * so customers ordering the same dish at the same time never block or conflict with each other.
     */
    @Modifying
    @Query("""
            update MenuItem m
            set m.popularityCount = case when m.popularityCount + :delta < 0 then 0 else m.popularityCount + :delta end
            where m.id = :id""")
    void adjustPopularity(@Param("id") Long id, @Param("delta") int delta);
}
