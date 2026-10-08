package io.github.vivekdavara.dispatch.assignment;

/**
 * An offer left OFFERED (accepted, declined or expired), published after the change commits. The courier socket
 * tells the courier, so an app showing the offer can take it off screen whichever way it ended.
 */
public record OfferClosed(Assignment assignment) {
}
