package io.github.vivekdavara.dispatch.zone;

/** A request named a zone that doesn't exist. */
public class UnknownZoneException extends RuntimeException {
    public UnknownZoneException(String zoneId) {
        super("unknown zone: " + zoneId);
    }
}
