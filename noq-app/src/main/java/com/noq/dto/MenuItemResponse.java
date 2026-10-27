package com.noq.dto;

import com.noq.entity.MenuItem;
import com.noq.enums.Category;

import java.math.BigDecimal;

/**
 * What the outside world sees of a menu item. We never return the JPA entity itself
 * (that would leak internal fields like "version" and risk lazy-loading / recursion problems).
 */
public record MenuItemResponse(
        Long id,
        String name,
        String description,
        Category category,
        String categoryLabel,
        String categoryEmoji,
        BigDecimal price,
        String imageUrl,
        boolean available,
        boolean active,
        boolean orderable,
        int popularityCount) {

    public static MenuItemResponse from(MenuItem m) {
        return new MenuItemResponse(m.getId(), m.getName(), m.getDescription(), m.getCategory(),
                m.getCategory().getLabel(), m.getCategory().getEmoji(), m.getPrice(), m.getImageUrl(),
                m.isAvailable(), m.isActive(), m.isOrderable(), m.getPopularityCount());
    }
}
