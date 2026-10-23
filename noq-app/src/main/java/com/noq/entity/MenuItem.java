package com.noq.entity;

import com.noq.enums.Category;
import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * A dish on the canteen menu. Hibernate maps this class to the {@code menu_item} table:
 * every field becomes a column, and every object becomes a row.
 */
@Entity
@Table(name = "menu_item", indexes = @Index(name = "idx_menu_item_category", columnList = "category"))
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // MySQL AUTO_INCREMENT
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 300)
    private String description;

    @Enumerated(EnumType.STRING) // stored as text ("SNACKS"), not as a fragile ordinal number
    @Column(nullable = false, columnDefinition = "varchar(20)") // plain VARCHAR: no MySQL ENUM/CHECK, so a new enum value needs no ALTER TABLE
    private Category category;

    @Column(nullable = false, precision = 8, scale = 2) // money: always BigDecimal, never double
    private BigDecimal price;

    @Column(name = "image_url", length = 300)
    private String imageUrl;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    /** Staff switch: false = "sold out" even if stock is left. */
    @Column(nullable = false)
    private boolean available = true;

    /** false = removed from the menu (soft delete keeps old orders intact). */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "popularity_count", nullable = false)
    private int popularityCount;

    /**
     * Optimistic locking: Hibernate adds "WHERE version = ?" to every UPDATE and increments it.
     * If two checkouts change the same item at the same time, the second one fails instead of
     * silently overwriting the stock.
     */
    @Version
    private Long version;

    /** True when a customer may add this item to an order right now. */
    public boolean isOrderable() {
        return active && available && availableQuantity > 0;
    }

    public void reduceStock(int quantity) {
        if (quantity > availableQuantity) {
            throw new IllegalStateException("Not enough stock for " + name);
        }
        availableQuantity -= quantity;
        popularityCount += quantity;
    }

    public void restoreStock(int quantity) {
        availableQuantity += quantity;
        popularityCount = Math.max(0, popularityCount - quantity);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public String getImageUrl() { return imageUrl; }
    public int getAvailableQuantity() { return availableQuantity; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public boolean isActive() { return active; }
    public int getPopularityCount() { return popularityCount; }
    public Long getVersion() { return version; }
}
