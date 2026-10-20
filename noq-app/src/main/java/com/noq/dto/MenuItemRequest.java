package com.noq.dto;

import com.noq.entity.MenuItem;
import com.noq.enums.Category;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Input for creating / editing a menu item. Used both by the REST API (JSON body)
 * and by the Thymeleaf staff form (form fields), so it is a plain class with getters/setters.
 */
public class MenuItemRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    private String name;

    @Size(max = 300, message = "Description must be at most 300 characters")
    private String description;

    @NotNull(message = "Category is required")
    private Category category;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "1.00", message = "Price must be at least 1.00")
    @DecimalMax(value = "5000.00", message = "Price must be at most 5000.00")
    @Digits(integer = 6, fraction = 2, message = "Price can have at most 2 decimal places")
    private BigDecimal price;

    @Size(max = 300, message = "Image URL must be at most 300 characters")
    @Pattern(regexp = "^$|^(https?://|/).+", message = "Image URL must start with http://, https:// or /")
    private String imageUrl;

    @NotNull(message = "Stock is required")
    @Min(value = 0, message = "Stock cannot be negative")
    @Max(value = 10000, message = "Stock must be at most 10000")
    private Integer availableQuantity = 0;

    private boolean available = true;

    public static MenuItemRequest from(MenuItem m) {
        MenuItemRequest r = new MenuItemRequest();
        r.name = m.getName();
        r.description = m.getDescription();
        r.category = m.getCategory();
        r.price = m.getPrice();
        r.imageUrl = m.getImageUrl();
        r.availableQuantity = m.getAvailableQuantity();
        r.available = m.isAvailable();
        return r;
    }

    /** Copies the item details onto the entity. Stock is NOT copied: it changes only through orders and the stock operation. */
    public void applyTo(MenuItem m) {
        m.setName(name.trim());
        m.setDescription(description == null ? null : description.trim());
        m.setCategory(category);
        m.setPrice(price);
        m.setImageUrl(imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim());
        m.setAvailable(available);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getAvailableQuantity() { return availableQuantity; }
    public void setAvailableQuantity(Integer availableQuantity) { this.availableQuantity = availableQuantity; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
}
