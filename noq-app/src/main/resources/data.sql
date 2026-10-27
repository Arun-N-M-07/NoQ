-- ============================================================
-- NoQ sample data. Runs at every start-up (spring.sql.init.mode=always).
-- INSERT IGNORE skips rows whose id already exists, so staff edits survive restarts.
-- Popularity counts start at 0 and grow from REAL orders.
-- ============================================================

INSERT IGNORE INTO menu_item (id, name, description, category, price, image_url, available, active, popularity_count, version) VALUES
(1,  'Masala Dosa', 'Crisp rice crepe with spiced potato filling, sambar and coconut chutney.', 'MEALS', 50.00, 'https://images.unsplash.com/photo-1668236543090-82eba5ee5976?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(2,  'Veg Biryani', 'Fragrant basmati rice layered with vegetables and whole spices, served with raita.', 'MEALS', 90.00, 'https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(3,  'Samosa (2 pcs)', 'Flaky pastry stuffed with spiced potato and peas, with mint chutney.', 'SNACKS', 20.00, 'https://images.unsplash.com/photo-1601050690597-df0568f70950?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(4,  'Cold Coffee', 'Chilled coffee blended with milk and ice cream.', 'BEVERAGES', 50.00, 'https://images.unsplash.com/photo-1461023058943-07fcbe16d735?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(5,  'Pav Bhaji', 'Buttery mashed vegetable curry with two toasted pav buns.', 'SNACKS', 60.00, 'https://images.unsplash.com/photo-1606491956689-2ea866880c84?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(6,  'Veg Burger', 'Crunchy veg patty, lettuce, tomato and house sauce in a toasted bun.', 'FAST_FOOD', 70.00, 'https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(7,  'Masala Chai', 'Strong tea brewed with ginger, cardamom and milk.', 'BEVERAGES', 15.00, 'https://images.unsplash.com/photo-1576092768241-dec231879fc3?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(8,  'Hakka Noodles', 'Wok-tossed noodles with crunchy vegetables and soy-chilli sauce.', 'FAST_FOOD', 70.00, 'https://images.unsplash.com/photo-1585032226651-759b368d7246?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(9,  'Idli Vada Combo', 'Two soft idlis and one medu vada with sambar and chutney.', 'MEALS', 45.00, 'https://images.unsplash.com/photo-1630383249896-424e482df921?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(10, 'North Indian Thali', 'Two curries, dal, rice, rotis, salad and a sweet. The full meal.', 'MEALS', 110.00, 'https://images.unsplash.com/photo-1567337710282-00832b415979?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(11, 'Paneer Butter Masala & Rice', 'Creamy tomato gravy with soft paneer cubes, served with jeera rice.', 'MEALS', 100.00, 'https://images.unsplash.com/photo-1588166524941-3bf61a9c41db?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(12, 'Tandoori Chicken (Half)', 'Chicken marinated in yoghurt and spices, roasted in the tandoor.', 'MEALS', 140.00, 'https://images.unsplash.com/photo-1626074353765-517a681e40be?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(13, 'Chicken Burger', 'Crispy fried chicken fillet with cheese, lettuce and mayo.', 'FAST_FOOD', 90.00, 'https://images.unsplash.com/photo-1571091718767-18b5b1457add?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(14, 'Veg Grilled Sandwich', 'Triple-layer grilled sandwich with vegetables, cheese and green chutney.', 'FAST_FOOD', 55.00, 'https://images.unsplash.com/photo-1528735602780-2552fd46c7af?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(15, 'Peri-Peri Fries', 'Golden fries tossed in spicy peri-peri seasoning.', 'FAST_FOOD', 60.00, 'https://images.unsplash.com/photo-1573080496219-bb080dd4f877?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(16, 'Margherita Pizza', 'Personal pizza with tomato sauce, mozzarella and basil.', 'FAST_FOOD', 120.00, 'https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(17, 'Papdi Chaat', 'Crisp papdi with potato, chickpeas, curd and tangy chutneys.', 'SNACKS', 40.00, 'https://images.unsplash.com/photo-1610192244261-3f33de3f55e4?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(18, 'Filter Coffee', 'South Indian decoction coffee with frothy hot milk.', 'BEVERAGES', 20.00, 'https://images.unsplash.com/photo-1561336313-0bd5e0b27ec8?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(19, 'Fresh Lime Soda', 'Sweet or salted lime soda with mint.', 'BEVERAGES', 30.00, 'https://images.unsplash.com/photo-1621263764928-df1444c5e859?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(20, 'Chocolate Donut', 'Soft ring donut dipped in chocolate with sprinkles.', 'DESSERTS', 45.00, 'https://images.unsplash.com/photo-1551024601-bec78aea704b?w=600&h=400&fit=crop', TRUE, TRUE, 0, 0),
(21, 'Gulab Jamun (2 pcs)', 'Warm milk dumplings soaked in cardamom sugar syrup.', 'DESSERTS', 30.00, NULL, TRUE, TRUE, 0, 0),
(22, 'Garden Salad Bowl', 'Seasonal vegetables, sprouts, paneer and lemon dressing.', 'MEALS', 80.00, 'https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=600&h=400&fit=crop', FALSE, TRUE, 0, 0),
(23, 'Chilli Paneer', 'Indo-Chinese paneer tossed with peppers and chilli sauce. (Seasonal - currently off the menu.)', 'SNACKS', 90.00, 'https://images.unsplash.com/photo-1645177628172-a94c1f96e6db?w=600&h=400&fit=crop', TRUE, FALSE, 0, 0);

-- Status names used by older versions -> current names, so their orders still load (no-op on a fresh database).
UPDATE customer_order SET status = CASE status WHEN 'READY' THEN 'READY_FOR_PICKUP' WHEN 'COMPLETED' THEN 'COLLECTED' ELSE 'PREPARING' END
WHERE status IN ('PLACED', 'CONFIRMED', 'READY', 'COMPLETED');

-- Demo logins (BCrypt hashes). kitchen / kitchen123 (KITCHEN)   staff / staff123 (STAFF)
-- Change these before any real use.
INSERT IGNORE INTO staff_user (id, username, password_hash, role, active) VALUES
(2, 'kitchen', '$2a$10$BVhQzvUqDaZMeN45PjStPu3D38qQEA1zaY84qA.WEanYaVG4M0.w6', 'KITCHEN', TRUE),
(3, 'staff',   '$2a$10$26L69.raB2nWHyHbxclmhuSSn1BcOEygWUCn6qfzCxSuwNQYbnEBS', 'STAFF', TRUE);
-- Databases created by older versions: the demo kitchen login becomes KITCHEN, and the old admin login is switched
-- off (kept, not deleted) because the ADMIN role no longer exists. No-op on a fresh database.
UPDATE staff_user SET role = 'KITCHEN' WHERE username = 'kitchen' AND role = 'STAFF';
UPDATE staff_user SET role = 'STAFF', active = FALSE WHERE role = 'ADMIN';
