package io.benwiegand.atvremote.receiver.ui.test;

import android.view.View;

import java.util.function.Consumer;
import java.util.function.Function;

public record ViewGrid(View rootView, View[][] grid) {

    public record Entry(int col, int row, View view) {}

    public int rows() {
        return grid.length;
    }

    public int cols() {
        if (grid.length == 0) return 0;
        return grid[0].length;
    }

    public View get(int col, int row) {
        return grid[row][col];
    }

    public View getOffsetFromCenter(int colOffset, int rowOffset) {
        int rowCenter = rows() / 2;
        int colCenter = cols() / 2;
        return get(colCenter + colOffset, rowCenter + rowOffset);
    }

    public View getOffsetFromCenter(DpadTestUtil.DpadDirection direction, int amount) {
        return switch (direction) {
            case DPAD_UP -> getOffsetFromCenter(0, -amount);
            case DPAD_DOWN -> getOffsetFromCenter(0, amount);
            case DPAD_LEFT -> getOffsetFromCenter(-amount, 0);
            case DPAD_RIGHT -> getOffsetFromCenter(amount, 0);
        };
    }

    public View getCenter() {
        return getOffsetFromCenter(0, 0);
    }

    public void forEach(Function<Entry, Boolean> entryConsumer) {
        for (int r = 0; r < rows(); r++) {
            for (int c = 0; c < cols(); c++) {
                if (!entryConsumer.apply(new Entry(c, r, get(c, r)))) {
                    return;
                }
            }
        }
    }

    public void forEach(Consumer<Entry> entryConsumer) {
        forEach(e -> {
            entryConsumer.accept(e);
            return true;
        });
    }
}
