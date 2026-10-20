package com.noq.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * One line of an order ("2 x Samosa"). The name and price are copied ("snapshotted") at order time,
 * so changing the menu price tomorrow does not change yesterday's bill.
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Many order items belong to one order -> foreign key column order_id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder customerOrder;

    // Many order items refer to one menu item -> foreign key column menu_item_id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(name = "item_name_snapshot", nullable = false, length = 80)
    private String itemNameSnapshot;

    @Column(name = "unit_price_snapshot", nullable = false, precision = 8, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;

    protected OrderItem() {
        // required by JPA
    }

    public OrderItem(MenuItem menuItem, int quantity) {
        this.menuItem = menuItem;
        this.itemNameSnapshot = menuItem.getName();
        this.unitPriceSnapshot = menuItem.getPrice();
        this.quantity = quantity;
        this.lineTotal = menuItem.getPrice().multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() { return id; }
    public CustomerOrder getCustomerOrder() { return customerOrder; }
    void setCustomerOrder(CustomerOrder customerOrder) { this.customerOrder = customerOrder; }
    public MenuItem getMenuItem() { return menuItem; }
    public String getItemNameSnapshot() { return itemNameSnapshot; }
    public BigDecimal getUnitPriceSnapshot() { return unitPriceSnapshot; }
    public int getQuantity() { return quantity; }
    public BigDecimal getLineTotal() { return lineTotal; }
}
