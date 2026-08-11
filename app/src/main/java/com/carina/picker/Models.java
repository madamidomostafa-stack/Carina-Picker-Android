package com.carina.picker;

final class Models {
    static final class PickLine {
        long id;
        String orderNo, branch, sku, location, status;
        int qty, picked, damage, notFound, seq;
        int classified() { return picked + damage + notFound; }
        int remaining() { return Math.max(0, qty - classified()); }
        boolean complete() { return qty > 0 && classified() >= qty; }
    }

    static final class OrderSummary {
        String orderNo = "", branch = "";
        int required, picked, damage, notFound, totalLines, doneLines;
        long elapsedMs;
        int completionPct() {
            return required <= 0 ? 0 : Math.min(100, Math.round((picked + damage + notFound) * 100f / required));
        }
    }
}
