package io.github.vivekdavara.dispatch.domain;

/**
 * Service tier of an order. The bonus is how many minutes of waiting the tier is worth in
 * {@link PriorityScore}.
 */
public enum OrderTier {
    STANDARD(0),
    PRIORITY(10);

    private final int bonusMinutes;

    OrderTier(int bonusMinutes) {
        this.bonusMinutes = bonusMinutes;
    }

    public int bonusMinutes() {
        return bonusMinutes;
    }
}
