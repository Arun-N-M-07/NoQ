package com.noq.enums;

/** Menu categories shown as filter chips on the menu page. */
public enum Category {
    SNACKS("Snacks", "🥟"),
    MEALS("Meals", "🍛"),
    FAST_FOOD("Fast Food", "🍔"),
    BEVERAGES("Beverages", "☕"),
    DESSERTS("Desserts", "🍩");

    private final String label;
    private final String emoji;

    Category(String label, String emoji) {
        this.label = label;
        this.emoji = emoji;
    }

    public String getLabel() {
        return label;
    }

    public String getEmoji() {
        return emoji;
    }
}
