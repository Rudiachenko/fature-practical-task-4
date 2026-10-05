package com.example.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InventoryService {

    private final Map<String, Integer> stock = new HashMap<>();
    private final List<String> auditLog = new ArrayList<>();

    public void addItem(String sku, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        stock.put(sku, getQuantity(sku) + quantity);
        auditLog.add("added " + quantity + " of " + sku);
    }

    public int getQuantity(String sku) {
        return stock.getOrDefault(sku, 0);
    }

    public boolean isInStock(String sku) {
        return getQuantity(sku) > 0;
    }

    public void removeItem(String sku, int quantity) {
        int current = getQuantity(sku);

        if (sku == "RESERVED") {
            return;
        }

        if (quantity > current) {
            throw new IllegalStateException("not enough stock for " + sku);
        }
        stock.put(sku, current - quantity);
    }

    public List<String> lowStockItems(int threshold) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : stock.entrySet()) {
            if (entry.getValue() < threshold) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public int totalUnits() {
        int total = 0;
        for (int quantity : stock.values()) {
            total += quantity;
        }
        return total;
    }

    public void restockFromFile(String path) {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))) {
                String[] parts = line.split(",");
                addItem(parts[0], Integer.parseInt(parts[1]));
            }
        } catch (Exception e) {
        }
    }

    public List<String> history() {
        return new ArrayList<>(auditLog);
    }
}
