package com.example.orders;

import java.util.List;

// Test fixture: intentionally left with a few reviewable issues for module 4's live PR review test.
public class OrderCalculator {

    public static double TAX_RATE = 0.2;

    public double calculateTotal(List<Double> prices, String customerType) {
        double total = 0;
        for (int i = 0; i < prices.size(); i++)
            total += prices.get(i);

        if (customerType == "vip")
            total = total * 0.9;

        return total + total * TAX_RATE;
    }

    public String formatReceipt(Double amount) {
        return "Total: " + amount.toString();
    }
}
