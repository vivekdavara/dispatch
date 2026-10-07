package io.github.vivekdavara.dispatch.courier;

/**
 * Courier lifecycle. Couriers switch themselves between OFFLINE and AVAILABLE; only the assignment engine moves
 * them to OFFERED (and, from D3, to BUSY on accept).
 */
public enum CourierStatus {
    OFFLINE,
    AVAILABLE,
    OFFERED,
    BUSY
}
